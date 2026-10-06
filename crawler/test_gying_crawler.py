import unittest
from unittest.mock import Mock, patch

from crawler.gying_crawler import (
    fetch_movie_resource_snapshot,
    parse_season,
    find_series_seasons,
    save_source_identity,
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

    def test_parses_parallel_search_arrays_and_fills_movie_series(self):
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
        self.assertEqual("揭秘日", items[0]["seriesName"])
        self.assertEqual(1, items[0]["season"])

    def test_movie_anime_and_tv_series_fields(self):
        for title, kind, expected in [
            ("复仇者联盟2：奥创纪元", "mv", ("复仇者联盟", 2)),
            ("破产姐妹 第六季", "tv", ("破产姐妹", 6)),
            ("星际迷航：奇异新世界 第四季", "tv", ("星际迷航：奇异新世界", 4)),
            ("示例动漫 Season 2", "ac", ("示例动漫", 2)),
            ("1917", "mv", ("1917", 1)),
            ("银翼杀手2049", "mv", ("银翼杀手2049", 1)),
        ]:
            self.assertEqual(expected, parse_season(title, kind))

    @patch("crawler.gying_crawler.fetch_catalog_movies")
    @patch("crawler.gying_crawler.search_movies")
    @patch("crawler.gying_crawler.fetch_movie_metadata")
    def test_star_trek_uses_search_not_twenty_catalog_pages(self, metadata, search, catalog):
        metadata.return_value = {"title": "星际迷航：奇异新世界 第四季"}
        search.return_value = [
            {"typeCode":"tv", "mid":str(n), "title":f"星际迷航：奇异新世界 第{n}季"}
            for n in [1,2,3]
        ] + [{"typeCode":"tv", "mid":"unrelated", "title":"星际迷航：发现号 第一季"}]
        rows = find_series_seasons("tv", "fourth", 20)
        self.assertEqual([1,2,3,4], [row["season"] for row in rows])
        catalog.assert_not_called()
        search.assert_called_once_with("星际迷航：奇异新世界", type_code="tv", mode=3, limit=100)

    def test_movie_installment_number_does_not_change_identity_season(self):
        from unittest.mock import MagicMock
        db = MagicMock()
        save_source_identity(db, "canonical", "GYING", "mv", "source", 3)
        values = db.cursor.return_value.__enter__.return_value.execute.call_args.args[1]
        self.assertEqual(0, values[4])
        save_source_identity(db, "canonical", "GYING", "tv", "source", 3)
        values = db.cursor.return_value.__enter__.return_value.execute.call_args.args[1]
        self.assertEqual(3, values[4])

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





class WeeklyPopularTest(unittest.TestCase):
    def test_exact_weekly_scope_and_limit(self):
        import json
        from crawler.gying_crawler import parse_weekly_popular
        for kind in ('mv', 'tv', 'ac'):
            markup = '_obj.inlist=' + json.dumps({'ty': kind, 't': ['甲', '乙'], 'i': ['A1', 'B2']}) + ';_obj.hits={"by":"week"};'
            rows = parse_weekly_popular(kind, markup, 1)
            self.assertEqual(1, len(rows))
            self.assertEqual(kind, rows[0]['typeCode'])
            self.assertEqual('A1', rows[0]['mid'])
            with self.assertRaises(RuntimeError):
                parse_weekly_popular(kind, markup.replace('"week"', '"month"'), 1)
    def test_challenge_and_empty_payload_fail_closed(self):
        from crawler.gying_crawler import parse_weekly_popular
        for markup in ('<title>Verification</title>', '_obj.inlist={"ty":"mv","t":[],"i":[]};_obj.hits={"by":"week"};'):
            with self.assertRaises(RuntimeError):
                parse_weekly_popular('mv', markup, 5)
    @patch('crawler.gying_crawler.site_get')
    def test_fetches_weekly_path_not_all_time_catalog(self, get):
        from crawler.gying_crawler import fetch_weekly_popular
        get.return_value = Mock(status_code=200, text='_obj.inlist={"ty":"tv","t":["测试"],"i":["A1"]};_obj.hits={"by":"week"};')
        self.assertEqual('A1', fetch_weekly_popular('tv')[0]['mid'])
        self.assertTrue(get.call_args.args[0].endswith('/hits/tv/week'))


class GyingCompactBtTest(unittest.TestCase):
    def payload(self):
        return {"hex": "fixture-ticket", "list": {
            "m": ["a" * 40, "B" * 40], "t": ["电影 1080p 中文字幕", "剧集 4K 中英字幕"],
            "u": ["BT1", "BT2"], "k": [0, 0], "s": ["1G", "2G"],
        }}

    def test_real_parallel_shape_generates_magnets_and_downloads_without_bt_requests(self):
        from crawler.gying_crawler import normalize_download_section, BASE_URL
        with patch("crawler.gying_crawler.site_get") as get:
            rows = normalize_download_section(self.payload(), "owned-account")
        self.assertEqual(["MAGNET", "TORRENT", "MAGNET", "TORRENT"], [x["type"] for x in rows])
        self.assertEqual("magnet:?xt=urn:btih:" + "b" * 40, rows[2]["url"])
        self.assertEqual(BASE_URL + "/dbt/BT1/fixture-ticket", rows[1]["url"])
        self.assertEqual(BASE_URL + "/bt/BT1", rows[0]["source_url"])
        self.assertEqual("电影 1080p 中文字幕", rows[0]["title"])
        self.assertTrue(all(x["provider"] == "P2P" and not x["is_own"] for x in rows))
        get.assert_not_called()

    def test_fetch_merges_compact_bt_and_existing_cloud_resources(self):
        from crawler.gying_crawler import fetch_download_resources
        response = Mock(status_code=200)
        response.json.return_value = {"code": 200, "downlist": self.payload(), "panlist": {
            "url": ["https://pan.quark.cn/s/fixture"], "name": ["cloud"], "user": ["owner"],
        }}
        with patch("crawler.gying_crawler.site_get", return_value=response) as get:
            rows = fetch_download_resources("mv", "MOVIE1", [], target_user="")
        self.assertEqual(5, len(rows))
        self.assertEqual(2, sum(x["type"] == "MAGNET" for x in rows))
        self.assertEqual(2, sum(x["type"] == "TORRENT" for x in rows))
        self.assertEqual(1, get.call_count)

    def test_folder_zip_and_missing_ticket_never_become_fake_torrents(self):
        from crawler.gying_crawler import normalize_download_section
        data = self.payload(); data["list"]["k"] = [1, 2]
        self.assertEqual([], normalize_download_section(data))
        data["list"]["k"] = [0, 0]; data.pop("hex")
        self.assertEqual(["MAGNET", "MAGNET"], [r["type"] for r in normalize_download_section(data)])

    def test_invalid_or_encoded_hash_and_misaligned_arrays_fail_closed(self):
        from crawler.gying_crawler import normalize_download_section
        data = self.payload(); data["list"]["m"][0] = "opaque-encrypted-value"
        with self.assertRaises(RuntimeError): normalize_download_section(data)
        data = self.payload(); data["list"]["t"].pop()
        with self.assertRaises(RuntimeError): normalize_download_section(data)

    def test_title_length_and_magnet_identity_stay_stable(self):
        from crawler.gying_crawler import normalize_download_section
        data = self.payload(); before = normalize_download_section(data)[0]["url"]
        data["list"]["t"][0] = "长标题" * 200
        row = normalize_download_section(data)[0]
        self.assertEqual(before, row["url"])
        self.assertEqual(255, len(row["title"]))


    def test_strict_resource_fetch_rejects_transport_http_json_and_upstream_failures(self):
        from crawler.gying_crawler import fetch_download_resources
        responses = [Mock(status_code=503), Mock(status_code=200)]
        responses[1].json.side_effect = ValueError("fixture HTML login page")
        for payload in ({"code": 401}, {"code": 200}, [], {"code": 403, "downlist": []}):
            response = Mock(status_code=200); response.json.return_value = payload
            responses.append(response)
        for response in responses:
            with self.subTest(response=response), patch("crawler.gying_crawler.site_get", return_value=response):
                with self.assertRaises(RuntimeError):
                    fetch_download_resources("mv", "MOVIE1", [], target_user="", strict=True)
        with patch("crawler.gying_crawler.site_get", side_effect=TimeoutError("fixture timeout")):
            with self.assertRaisesRegex(RuntimeError, "resource request failed"):
                fetch_download_resources("mv", "MOVIE1", [], target_user="", strict=True)

    def test_strict_resource_fetch_accepts_explicit_empty_and_compact_resources(self):
        from crawler.gying_crawler import fetch_download_resources
        for payload, count in (({"code": 200, "downlist": [], "panlist": []}, 0),
                               ({"code": 200, "downlist": self.payload()}, 4)):
            response = Mock(status_code=200); response.json.return_value = payload
            with patch("crawler.gying_crawler.site_get", return_value=response) as get:
                rows = fetch_download_resources("mv", "MOVIE1", [], target_user="", strict=True)
                self.assertEqual(count, len(rows))
                self.assertEqual(1, get.call_count)

    def test_resource_only_api_enables_strict_fetch(self):
        from crawler.gying_crawler import GyingSourceApiHandler
        handler = object.__new__(GyingSourceApiHandler)
        handler.path = "/resources/mv/MOVIE1"
        handler.authorized = Mock(return_value=True)
        handler.send_json = Mock()
        with patch("crawler.gying_crawler.fetch_download_resources", return_value=[]) as fetch:
            handler.do_GET()
        fetch.assert_called_once_with("mv", "MOVIE1", [], target_user="", strict=True)
        handler.send_json.assert_called_once_with(200, {"resources": []})

    def test_strict_bt_expansion_propagates_failure_and_empty_details(self):
        from crawler.gying_crawler import expand_bt_resources, BASE_URL
        resources = [{"url": BASE_URL + "/bt/BT1"}]
        with patch("crawler.gying_crawler.fetch_bt_resources", side_effect=RuntimeError("fixture unavailable")):
            with self.assertRaises(RuntimeError): expand_bt_resources(resources, strict=True)
        with patch("crawler.gying_crawler.fetch_bt_resources", return_value=[]):
            with self.assertRaises(RuntimeError): expand_bt_resources(resources, strict=True)



class P2pReleaseSelectionTest(unittest.TestCase):
    def release(self, identity, title):
        return [{"source_ref": identity, "title": title, "provider": "P2P", "type": kind,
                 "url": ("magnet:?xt=urn:btih:" if kind == "MAGNET" else "https://example.invalid/") + identity}
                for kind in ("MAGNET", "TORRENT")]

    def test_only_one_release_per_resolution_with_chinese_preferred(self):
        from crawler.gying_crawler import select_p2p_versions
        rows = (self.release("a", "Movie.1080p.English") + self.release("b", "Movie.1080p.中文字幕")
                + self.release("c", "Movie.1080p.中英字幕") + self.release("d", "Movie.2160p.HDR")
                + self.release("e", "Movie.720p.中字"))
        result = select_p2p_versions(rows)
        self.assertEqual(["b", "b", "d", "d"], [r["source_ref"] for r in result])
        self.assertEqual(["1080P", "1080P", "4K", "4K"], [r["quality"] for r in result])
        self.assertEqual("中文字幕（来源标注）", result[0]["subtitle"])

    def test_without_explicit_chinese_subtitles_keeps_no_p2p_only_cloud(self):
        from crawler.gying_crawler import select_p2p_versions
        cloud = {"type": "DISK", "provider": "QUARK", "url": "https://pan.quark.cn/s/fixture"}
        rows = self.release("a", "Movie.1080p.国语") + self.release("b", "Movie.4K.无中文字幕")
        self.assertEqual([cloud], select_p2p_versions(rows + [cloud]))

    def test_subtitle_markers_and_negative_labels(self):
        from crawler.gying_crawler import has_chinese_subtitle_label
        for title in ("内嵌中字", "简中", "繁中", "简繁英字幕", "CHS.ENG", "CHT", "Chinese.Subtitles"):
            with self.subTest(title=title): self.assertTrue(has_chinese_subtitle_label({"title": title}))
        for title in ("国语配音", "中文音轨", "中英双语配音", "中英音轨", "无中字", "无字幕", "无中文字幕", "no Chinese subtitles"):
            with self.subTest(title=title): self.assertFalse(has_chinese_subtitle_label({"title": title}))

    def test_only_unsupported_or_unknown_resolution_is_skipped(self):
        from crawler.gying_crawler import select_p2p_versions
        for title in ("Movie.720p.中字", "Movie.8K.中字", "Movie.中文字幕"):
            self.assertEqual([], select_p2p_versions(self.release("a", title)))

    def test_chinese_magnet_only_beats_uncertain_complete_pair(self):
        from crawler.gying_crawler import select_p2p_versions
        rows = self.release("a", "Movie.1080p.English") + self.release("b", "Movie.1080p.中字")[:1]
        rows += self.release("c", "Movie.4K.简中") * 2
        result = select_p2p_versions(rows)
        self.assertEqual(["b", "c", "c"], [r["source_ref"] for r in result])



class TorrentMetadataFileTest(unittest.TestCase):
    def content(self):
        info = b'd6:lengthi1e4:name1:x12:piece lengthi1e6:pieces20:' + b'x' * 20 + b'e'
        return b'd4:info' + info + b'e', __import__('hashlib').sha1(info).hexdigest()

    def test_info_hash_uses_original_bencoded_bytes(self):
        from crawler.gying_crawler import torrent_info_hash
        data, expected = self.content()
        self.assertEqual(expected, torrent_info_hash(data))

    def test_html_oversized_truncated_and_duplicate_info_are_rejected(self):
        from crawler.gying_crawler import torrent_info_hash
        good, _ = self.content()
        for data in (b'<html>login</html>', b'd' + b'x' * (2 * 1024 * 1024), good[:-1], good + b'extra',
                     b'd4:infod6:pieces20:' + b'x' * 20 + b'e4:infod6:pieces20:' + b'x' * 20 + b'ee'):
            with self.subTest(size=len(data)):
                with self.assertRaises(RuntimeError): torrent_info_hash(data)

    def resources(self, expected):
        from crawler.gying_crawler import BASE_URL
        return [{"type": "MAGNET", "source_ref": "BT1", "url": "magnet:?xt=urn:btih:" + expected},
                {"type": "TORRENT", "source_ref": "BT1", "url": BASE_URL + "/dbt/BT1/ticket"}]

    def test_download_is_bounded_verified_and_has_no_redirects(self):
        from crawler.gying_crawler import fetch_torrent_file
        data, expected = self.content()
        response = Mock(status_code=200); response.iter_content.return_value = [data]
        with patch("crawler.gying_crawler.fetch_download_resources", return_value=self.resources(expected)),              patch("crawler.gying_crawler.site_get", return_value=response) as get:
            result = fetch_torrent_file("mv", "MOVIE1", "BT1")
        self.assertEqual(expected, result["infoHash"])
        self.assertEqual(data, __import__('base64').b64decode(result["dataBase64"]))
        self.assertFalse(get.call_args.kwargs["allow_redirects"])
        self.assertTrue(get.call_args.kwargs["stream"])
        response.close.assert_called_once()

    def test_download_refuses_hash_mismatch_and_redirect_status(self):
        from crawler.gying_crawler import fetch_torrent_file
        data, expected = self.content()
        for status, info_hash in ((302, expected), (200, "a" * 40)):
            response = Mock(status_code=status); response.iter_content.return_value = [data]
            with patch("crawler.gying_crawler.fetch_download_resources", return_value=self.resources(info_hash)),                  patch("crawler.gying_crawler.site_get", return_value=response):
                with self.assertRaises(RuntimeError): fetch_torrent_file("mv", "MOVIE1", "BT1")
            response.close.assert_called_once()

    def test_download_denies_untrusted_endpoint_before_request(self):
        from crawler.gying_crawler import fetch_torrent_file
        _, expected = self.content(); resources = self.resources(expected)
        resources[1]["url"] = "http://127.0.0.1/private.torrent"
        with patch("crawler.gying_crawler.fetch_download_resources", return_value=resources),              patch("crawler.gying_crawler.site_get") as get:
            with self.assertRaises(RuntimeError): fetch_torrent_file("mv", "MOVIE1", "BT1")
        get.assert_not_called()

if __name__ == "__main__":
    unittest.main()
