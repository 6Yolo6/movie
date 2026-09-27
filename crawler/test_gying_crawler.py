import unittest
from unittest.mock import Mock, patch

from crawler.gying_crawler import (
    fetch_movie_resource_snapshot,
    list_my_pan_resources,
    gying_search_type,
    normalize_search_mode,
    normalize_search_items,
    preserve_existing_resource_source,
    publish_pan_resource,
)


class GyingSearchParserTest(unittest.TestCase):

    def test_maps_search_categories_and_clamps_mode(self):
        self.assertEqual(0, gying_search_type())
        self.assertEqual(1, gying_search_type("mv"))
        self.assertEqual(2, gying_search_type("tv"))
        self.assertEqual(3, gying_search_type("ac"))
        self.assertEqual(1, normalize_search_mode(0))
        self.assertEqual(2, normalize_search_mode("2"))
        self.assertEqual(3, normalize_search_mode(99))

    def test_parses_parallel_search_arrays_and_keeps_movie_season_empty(self):
        payload = {
            "l": {
                "daoyan": [
                    "\u53f2\u8482\u6587\u00b7\u65af\u76ae\u5c14\u4f2f\u683c",
                    "Terje Toftenes / Truls Toftenes",
                ],
                "zhuyan": [
                    "\u827e\u7c73\u8389\u00b7\u5e03\u6717\u7279 / "
                    "\u4e54\u4ec0\u00b7\u5965\u5eb7\u7eb3",
                    "Edgar D. Mitchell / Richard Dolan",
                ],
                "info": [
                    "\u7f8e\u56fd / \u5267\u60c5 / \u79d1\u5e7b",
                    "\u632a\u5a01 / \u7eaa\u5f55",
                ],
                "title": [
                    "\u63ed\u79d8\u65e5",
                    "\u4e34\u8fd1\u7684\u63ed\u79d8\u65e5",
                ],
                "name": ["Disclosure Day", "The Day Before Disclosure"],
                "ename": ["The Dish", ""],
                "year": [2026, 2010],
                "d": ["mv", "mv"],
                "i": ["0pEK", "xR48"],
            }
        }

        items = normalize_search_items(payload, "mv", 20)

        self.assertEqual(["0pEK", "xR48"], [item["mid"] for item in items])
        self.assertEqual("\u63ed\u79d8\u65e5", items[0]["title"])
        self.assertEqual(2026, items[0]["year"])
        self.assertEqual(
            ["\u53f2\u8482\u6587\u00b7\u65af\u76ae\u5c14\u4f2f\u683c"],
            items[0]["directors"],
        )
        self.assertIsNone(items[0]["seriesName"])
        self.assertIsNone(items[0]["season"])

    def test_preserves_existing_owned_resource_source(self):
        self.assertEqual(
            "RESOURCE_HUB",
            preserve_existing_resource_source("RESOURCE_HUB"),
        )
        self.assertEqual(
            "GYING_PUBLISHED",
            preserve_existing_resource_source("GYING_PUBLISHED"),
        )
        self.assertEqual("GYING", preserve_existing_resource_source(None))

    @patch("crawler.gying_crawler.site_post")
    def test_publishes_pan_resource_with_current_binding_contract(self, site_post):
        response = Mock()
        response.ok = True
        response.json.return_value = {"code": 200}
        site_post.return_value = response

        result = publish_pan_resource(
            "mv",
            "VeewM",
            "4K/2160P Minions & Monsters (2026)",
            "https://pan.quark.cn/s/example",
        )

        self.assertEqual({"code": 200}, result)
        endpoint, = site_post.call_args.args
        self.assertTrue(endpoint.endswith("/res/pan/add"))
        self.assertFalse(endpoint.endswith("/res/pan/add/mv/VeewM"))
        self.assertEqual(
            {
                "title": "4K/2160P Minions & Monsters (2026)",
                "panurl": "https://pan.quark.cn/s/example",
                "panpw": "",
                "is": "0",
                "binds[0][dir]": "mv",
                "binds[0][id]": "VeewM",
            },
            site_post.call_args.kwargs["data"],
        )
        self.assertEqual(
            "XMLHttpRequest",
            site_post.call_args.kwargs["headers"]["X-Requested-With"],
        )


class GyingMovieSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch("crawler.gying_crawler.TARGET_USER", "owner"))
        self.metadata = self.enterContext(patch(
            "crawler.gying_crawler.fetch_movie_metadata",
            return_value={"title": "测试电影", "name": "Test Movie", "year": 2026},
        ))
        self.response = Mock(status_code=200)
        self.response.json.return_value = {"panlist": []}
        self.site_get = self.enterContext(patch(
            "crawler.gying_crawler.site_get", return_value=self.response,
        ))
        self.account_scan = self.enterContext(patch(
            "crawler.gying_crawler.list_my_pan_resources",
            side_effect=AssertionError("movie snapshots must not scan account resources"),
        ))
        self.account_page = self.enterContext(patch(
            "crawler.gying_crawler.fetch_user_content_page",
            side_effect=AssertionError("movie snapshots must not read account pages"),
        ))

    def tearDown(self):
        self.account_scan.assert_not_called()
        self.account_page.assert_not_called()

    def test_uses_detail_uploaders_to_identify_own_resources(self):
        self.response.json.return_value = {"panlist": [
            {"id": "owned-quark", "url": "https://pan.quark.cn/s/owned", "user": "owner"},
            {"id": "owned-xunlei", "url": "https://pan.xunlei.com/s/owned", "user": "owner"},
            {"id": "other", "url": "https://pan.quark.cn/s/other", "user": "someone-else", "is_own": True},
            {"id": "unknown", "url": "https://pan.quark.cn/s/unknown", "is_own": True},
        ]}

        snapshot = fetch_movie_resource_snapshot("mv", "MOVIE1")

        self.assertEqual("MOVIE1", snapshot["mid"])
        self.assertEqual("Test Movie", snapshot["titleEn"])
        self.assertEqual(2026, snapshot["year"])
        self.assertEqual(4, len(snapshot["resources"]))
        self.assertEqual(["owned-quark", "owned-xunlei"],
                         [r["source_id"] for r in snapshot["ownResources"]])
        self.assertEqual([True, True, False, False],
                         [r["is_own"] for r in snapshot["resources"]])
        self.metadata.assert_called_once_with("mv", "MOVIE1")
        self.site_get.assert_called_once()
        self.assertTrue(self.site_get.call_args.args[0].endswith("/res/downurl/mv/MOVIE1"))

    def test_identifies_own_resources_from_parallel_detail_arrays(self):
        self.response.json.return_value = {"panlist": {
            "id": ["mine", "theirs"],
            "name": ["我的资源", "其他资源"],
            "url": ["https://pan.quark.cn/s/mine", "https://pan.quark.cn/s/theirs"],
            "user": ["owner", "other"],
            "tname": ["夸克", "夸克"],
        }}

        snapshot = fetch_movie_resource_snapshot("tv", "SERIES1")

        self.assertEqual(2, len(snapshot["resources"]))
        self.assertEqual(["mine"], [r["source_id"] for r in snapshot["ownResources"]])

    def test_empty_detail_does_not_fall_back_to_account_scan(self):
        snapshot = fetch_movie_resource_snapshot("mv", "EMPTY")

        self.assertEqual([], snapshot["resources"])
        self.assertEqual([], snapshot["ownResources"])
        self.site_get.assert_called_once()

    def test_other_uploaders_do_not_trigger_account_fallback(self):
        self.response.json.return_value = {"panlist": [
            {"id": "other", "url": "https://pan.quark.cn/s/other", "user": "other"},
        ]}

        snapshot = fetch_movie_resource_snapshot("mv", "OTHER")

        self.assertEqual(1, len(snapshot["resources"]))
        self.assertEqual([], snapshot["ownResources"])

    def test_missing_target_account_never_claims_unattributed_resources(self):
        self.response.json.return_value = {"panlist": [
            {"id": "unknown", "url": "https://pan.quark.cn/s/unknown"},
        ]}
        with patch("crawler.gying_crawler.TARGET_USER", ""):
            snapshot = fetch_movie_resource_snapshot("mv", "UNKNOWN")

        self.assertEqual([], snapshot["ownResources"])
        self.assertFalse(snapshot["resources"][0]["is_own"])

    def test_repeated_movie_queries_only_read_their_own_detail_resources(self):
        fetch_movie_resource_snapshot("mv", "FIRST")
        fetch_movie_resource_snapshot("tv", "SECOND")

        self.assertEqual(2, self.site_get.call_count)
        self.assertTrue(self.site_get.call_args_list[0].args[0].endswith("/res/downurl/mv/FIRST"))
        self.assertTrue(self.site_get.call_args_list[1].args[0].endswith("/res/downurl/tv/SECOND"))


class GyingExplicitResourceSyncTest(unittest.TestCase):
    @patch("crawler.gying_crawler.fetch_user_content_page")
    def test_explicit_account_sync_still_supports_pagination(self, fetch_page):
        fetch_page.side_effect = [
            {"resources": [{"source_id": "first"}], "page": {"curr": 1, "pages": 2}},
            {"resources": [{"source_id": "second"}], "page": {"curr": 2, "pages": 2}},
        ]

        result = list_my_pan_resources(limit=10)

        self.assertEqual(["first", "second"], [r["source_id"] for r in result])
        self.assertEqual([1, 2], [call.args[0] for call in fetch_page.call_args_list])

    @patch("crawler.gying_crawler.list_my_pan_resources")
    @patch("crawler.gying_crawler.site_post")
    def test_duplicate_publish_hint_is_not_swallowed_or_resubmitted(self, site_post, scan):
        response = Mock(ok=True)
        response.json.return_value = {"code": 400, "msg": "该资源已存在，请勿重复发布"}
        site_post.return_value = response

        with self.assertRaisesRegex(RuntimeError, "该资源已存在，请勿重复发布"):
            publish_pan_resource("mv", "MOVIE1", "电影资源", "https://pan.quark.cn/s/existing")

        site_post.assert_called_once()
        scan.assert_not_called()


if __name__ == "__main__":
    unittest.main()
