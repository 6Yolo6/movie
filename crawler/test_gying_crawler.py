import unittest
from unittest.mock import Mock, patch

from crawler.gying_crawler import (
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


if __name__ == "__main__":
    unittest.main()
