"""Maintenance HTTP probes; no production requests or process changes."""
import importlib.util
from pathlib import Path
import unittest
import urllib.error
import urllib.request
from unittest.mock import MagicMock, patch


spec = importlib.util.spec_from_file_location(
    "restart_health", Path(__file__).parents[1] / "weekly_restart.py"
)
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class RestartHealth(unittest.TestCase):
    url = "https://entrypoint.invalid/"

    def response(self, status):
        response = MagicMock()
        response.status = status
        response.__enter__.return_value = response
        return response

    def test_browser_request_avoids_default_urllib_rejection(self):
        response = self.response(200)
        opener = MagicMock()

        def serve(request, timeout):
            if not isinstance(request, urllib.request.Request) or (
                request.get_header("User-agent") != "Mozilla/5.0"
            ):
                raise urllib.error.HTTPError(self.url, 403, "1010", None, None)
            self.assertEqual(request.full_url, self.url)
            self.assertEqual(timeout, 5)
            return response

        opener.open.side_effect = serve
        with patch.object(w.urllib.request, "build_opener", return_value=opener) as build:
            self.assertTrue(w.healthy(self.url))
        handler = build.call_args.args[0]
        self.assertIsInstance(handler, urllib.request.ProxyHandler)
        self.assertEqual(handler.proxies, {})
        response.__exit__.assert_called_once()

    def test_only_http_200_is_healthy(self):
        for status in (201, 204, 403, 503):
            with self.subTest(status=status):
                opener = MagicMock()
                opener.open.return_value = self.response(status)
                with patch.object(w.urllib.request, "build_opener", return_value=opener):
                    self.assertFalse(w.healthy(self.url))

    def test_http_and_transport_errors_remain_unhealthy(self):
        errors = (
            urllib.error.HTTPError(self.url, 403, "Forbidden", None, None),
            urllib.error.URLError("connection refused"),
            TimeoutError("timed out"),
        )
        for error in errors:
            with self.subTest(error=type(error).__name__):
                opener = MagicMock()
                opener.open.side_effect = error
                with patch.object(w.urllib.request, "build_opener", return_value=opener):
                    self.assertFalse(w.healthy(self.url))


if __name__ == "__main__":
    unittest.main()
