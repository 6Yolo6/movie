"""Offline tests using real requests.Response decoding and instrumented raw streams."""
import io
import unittest
from unittest.mock import Mock, patch

import requests
from crawler import gying_crawler as source


class CountingStream(io.BytesIO):
    def __init__(self, body, fail_after=None):
        super().__init__(body)
        self.bytes_read = 0
        self.fail_after = fail_after

    def read(self, size=-1):
        if self.fail_after is not None and self.bytes_read >= self.fail_after:
            raise OSError("fixture stream failure")
        result = super().read(size)
        self.bytes_read += len(result)
        return result


def response(body, status=200, headers=None, fail_after=None):
    result = requests.Response()
    result.status_code = status
    result.encoding = "utf-8"
    result.headers.update(headers or {})
    result.raw = CountingStream(body, fail_after)
    result.close = Mock(wraps=result.close)
    return result


class BoundedSiteGetTest(unittest.TestCase):
    def setUp(self):
        self.session = Mock()
        for name, replacement in (("get_site_session", Mock(return_value=self.session)),
                                  ("wait_for_site_request_slot", Mock())):
            patcher = patch.object(source, name, replacement)
            patcher.start()
            self.addCleanup(patcher.stop)

    def get(self, *responses, limit=128, **kwargs):
        self.session.get.side_effect = responses
        return source.site_get("https://fixture.invalid/torrent", max_response_bytes=limit, **kwargs)

    def test_body_is_bounded_before_real_text_and_json_probes(self):
        item = response(b"x" * (3 * 1024 * 1024), headers={"Content-Type": "application/x-bittorrent"})
        with patch.object(source, "is_pow_challenge_response", wraps=source.is_pow_challenge_response) as probe:
            with self.assertRaisesRegex(RuntimeError, "allowed size"):
                self.get(item, limit=2 * 1024 * 1024)
            probe.assert_not_called()
        self.assertLessEqual(item.raw.bytes_read, 2 * 1024 * 1024 + 65536)
        self.assertLess(item.raw.bytes_read, 3 * 1024 * 1024)
        item.close.assert_called_once()
        self.assertTrue(item.raw.closed)

    def test_declared_oversize_is_rejected_without_reading_the_body(self):
        item = response(b"short", headers={"Content-Length": "129"})
        with self.assertRaisesRegex(RuntimeError, "allowed size"):
            self.get(item)
        self.assertEqual(0, item.raw.bytes_read)
        item.close.assert_called_once()

    def test_missing_invalid_and_false_lengths_never_disable_stream_limit(self):
        for header in ({}, {"Content-Length": "0"}, {"Content-Length": "bad"}, {"Content-Length": "-1"}):
            with self.subTest(header=header):
                item = response(b"x" * 1024, headers=header)
                with self.assertRaisesRegex(RuntimeError, "allowed size"):
                    self.get(item)
                self.assertLessEqual(item.raw.bytes_read, 129)
                item.close.assert_called_once()

    def test_exact_limit_preserves_cached_content_and_stream_interface(self):
        body = b"d" + b"x" * 127
        item = response(body, headers={"Content-Type": "application/json", "Content-Length": "128"})
        result = self.get(item, stream=False, allow_redirects=False, timeout=3)
        try:
            self.assertIs(item, result)
            self.assertEqual(body, result.content)
            self.assertEqual(body, b"".join(result.iter_content(16)))
            self.assertTrue(result._content_consumed)
            self.assertEqual(128, item.raw.bytes_read)
            self.session.get.assert_called_once_with("https://fixture.invalid/torrent", timeout=3,
                                                    headers={}, stream=True, allow_redirects=False)
            item.close.assert_not_called()  # ownership is transferred to the caller
        finally:
            result.close()

    def test_empty_body_is_valid_at_the_transport_layer(self):
        item = response(b"")
        result = self.get(item)
        self.assertEqual(b"", result.content)
        result.close()

    def test_json_pow_challenge_and_retried_body_both_remain_bounded(self):
        challenge = response(b'{"code":419}', status=419)
        good = response(b"torrent fixture")
        with patch.object(source, "solve_browser_pow", return_value=True) as solve:
            result = self.get(challenge, good)
        self.assertIs(result, good)
        self.assertEqual(b"torrent fixture", result.content)
        solve.assert_called_once_with(self.session, "https://fixture.invalid/torrent")
        challenge.close.assert_called_once()
        self.assertEqual(2, self.session.get.call_count)
        for call in self.session.get.call_args_list:
            self.assertTrue(call.kwargs["stream"])
            self.assertNotIn("max_response_bytes", call.kwargs)
        result.close()

    def test_login_then_pow_retry_keeps_existing_authentication_order(self):
        login = response("未登录，访问受限".encode(), headers={"Content-Type": "application/x-bittorrent"})
        challenge = response(b'{"code":419}')
        good = response(b"torrent fixture")
        with patch.object(source, "login_site_session", return_value=True) as authenticate, \
             patch.object(source, "solve_browser_pow", return_value=True) as solve:
            result = self.get(login, challenge, good)
        authenticate.assert_called_once_with(self.session)
        solve.assert_called_once_with(self.session, "https://fixture.invalid/torrent")
        login.close.assert_called_once()
        challenge.close.assert_called_once()
        self.assertEqual(3, self.session.get.call_count)
        self.assertEqual(b"torrent fixture", result.content)
        result.close()

    def test_html_pow_challenge_is_still_recognized(self):
        challenge = response(b"<html><script>powSolve()</script></html>")
        good = response(b"torrent fixture")
        with patch.object(source, "solve_browser_pow", return_value=True) as solve:
            result = self.get(challenge, good)
        solve.assert_called_once()
        challenge.close.assert_called_once()
        result.close()

    def test_unsolved_challenge_is_returned_not_silently_accepted_or_retried(self):
        challenge = response(b'{"code":419}', status=419)
        with patch.object(source, "solve_browser_pow", return_value=False):
            result = self.get(challenge)
        self.assertIs(result, challenge)
        self.assertEqual(419, result.status_code)
        self.session.get.assert_called_once()
        result.close()

    def test_oversized_auth_retry_closes_both_responses(self):
        challenge = response(b'{"code":419}')
        oversized = response(b"x" * 1024)
        with patch.object(source, "solve_browser_pow", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "allowed size"):
                self.get(challenge, oversized)
        challenge.close.assert_called_once()
        oversized.close.assert_called_once()
        self.assertLessEqual(oversized.raw.bytes_read, 129)

    def test_stream_failure_closes_partially_consumed_response(self):
        item = response(b"x" * 100000, fail_after=65536)
        with self.assertRaisesRegex(OSError, "fixture stream failure"):
            self.get(item, limit=150000)
        self.assertEqual(65536, item.raw.bytes_read)
        item.close.assert_called_once()
        self.assertTrue(item.raw.closed)

    def test_authentication_exception_closes_current_response(self):
        challenge = response(b'{"code":419}')
        with patch.object(source, "solve_browser_pow", side_effect=RuntimeError("fixture auth failure")):
            with self.assertRaisesRegex(RuntimeError, "fixture auth failure"):
                self.get(challenge)
        challenge.close.assert_called_once()

    def test_failed_retry_request_does_not_leave_superseded_response_open(self):
        challenge = response(b'{"code":419}')
        with patch.object(source, "solve_browser_pow", return_value=True):
            with self.assertRaises(requests.Timeout):
                self.get(challenge, requests.Timeout("fixture timeout"))
        challenge.close.assert_called_once()

    def test_invalid_limits_fail_before_any_request(self):
        for limit in (0, -1, True, "10", 1.5):
            with self.subTest(limit=limit), self.assertRaises(ValueError):
                self.get(limit=limit)
        self.session.get.assert_not_called()

    def test_non_bounded_call_keeps_original_request_options(self):
        item = response(b'{"fixture":true}')
        self.session.get.return_value = item
        result = source.site_get("https://fixture.invalid/metadata", headers={"Referer": "fixture"})
        self.assertEqual({"fixture": True}, result.json())
        self.session.get.assert_called_once_with("https://fixture.invalid/metadata", timeout=10,
                                                headers={"Referer": "fixture"})
        result.close()


if __name__ == "__main__":
    unittest.main()
