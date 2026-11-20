# TMDB 列表页码公开文档核对

研究日期：2026-10-29。只读取官方公开文档，未读取凭据、请求生产 API 或修改在线配置。

TMDB 官方 [Errors](https://developer.themoviedb.org/docs/errors) 统一给出错误码 **22 / HTTP 400**：

> Invalid page: Pages start at 1 and max at 500. They are expected to be an integer.

本项目继续保留现有 **1–500** 配置范围与游标，不扩展 Trending 到 1000，也不新增 GYING 50 页限制。普通列表/Discover 的 500 页与官方论坛索引对 Trending 的 1000 页说明必须区分，不能只靠通用错误码 22 否定 Trending 例外。

[TMDB 官方论坛](https://www.themoviedb.org/talk/668b027390c809787099e77d) 及[另一官方讨论](https://www.themoviedb.org/talk/629be4627e12f0721cbf6ec4) 的可见搜索索引摘要区分普通列表/Discover 500 页与 Trending 1000 页。本轮仅取得索引摘要，直接访问返回 403；未完整建立作者、角色、精确日期或全文，也未用生产 API 请求第 501/1000 页。该材料用于说明文档边界，不能写成已在线实测上限。

| 项目来源 | 官方 reference | 提取到的请求 page schema | 官方旧示例 total_pages |
| --- | --- | --- | --- |
| trending movie/day | [Movies](https://developer.themoviedb.org/reference/trending-movies) | 请求参数未列 page；time_window 为 day/week，默认 day | 1000 |
| trending tv/day | [TV](https://developer.themoviedb.org/reference/trending-tv) | 请求参数未列 page | 1000 |
| popular movie | [Popular](https://developer.themoviedb.org/reference/movie-popular-list) | integer / int32 / default 1；未重复最大值 | 38029 |
| popular tv | [Popular](https://developer.themoviedb.org/reference/tv-series-popular-list) | integer / int32 / default 1；未重复最大值 | 7416 |
| top-rated movie | [Top Rated](https://developer.themoviedb.org/reference/movie-top-rated-list) | integer / int32 / default 1；未重复最大值 | 552 |
| top-rated tv | [Top Rated](https://developer.themoviedb.org/reference/tv-series-top-rated-list) | integer / int32 / default 1；未重复最大值 | 142 |
| upcoming movie | [Upcoming](https://developer.themoviedb.org/reference/movie-upcoming-list) | 本轮未单独提取；后续请求超时/失败 | 19 |

这些 total_pages 是官方文档的历史响应示例，不是本次实时 API 返回，也不代表当前网站的片库规模。普通 popular 示例 38029 不表示可读 38029 页；Trending 的 1000 须结合官方论坛索引说明，不能据通用错误文字一概截断其官方能力。

Trending 两个 reference 的请求 parameters 未列 page，虽然响应示例含 page / total_pages / total_results。不能把它们描述成“每个 reference 都明确写出 page 默认 1、最大 500”。本站本轮保留 500 是现有配置范围，不是对 Trending 官方 1000 能力的否定。Upcoming 的请求 schema 未在本轮单独核完，也不据此猜测额外限制。

响应 schema 中某些分页字段写有 default 0，这是响应模型的 schema 标记，不能当作合法请求页码的默认值；已提取的四个普通列表请求 page 默认值均为 1。

公开摘要、原文、链接、提取到的 schema 和 Markdown SHA-256 在同目录 tmdb-pages.json。读取中存在间歇性连接失败，未保存未核实的参数为事实。
