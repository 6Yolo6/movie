# 部署后真实网站四轮观测

观测时间：2026-11-03T13:53:54.960937+00:00 至 2026-11-03T13:55:10.868131+00:00（UTC，Asia/Shanghai 为 2026-11-03 21:53–21:55）。

通过真实 nginx 来源，以临时普通 USER 会话提交。两题四轮，每轮仅一次，无重试。机械 4/4 通过；这不等于四条回答满足需求。不是密码登录、浏览器或独立题集验收。

报告规范 JSON SHA-256：`f027788aaf70e9968ec1830885554ab06cb2324dce7878480cec8249076273fc`。报告原文件字节 SHA-256：`da50298bff1fc6136862b35c418f5e2b056f91ce7666d63ede5ee06c630fc820`。回答绑定使用当前 v2 复核协议的规范 JSON 哈希。

下列回答来自本次脱敏观测；四条脱敏回答的哈希均与原回答哈希相同。生产会话、分享链接和资源地址不在本文中。

## dev-v3-06 / 第 1 轮

请求：先找2000至2010年上映、最多110分钟的音乐电影，给两部并列出年份。

Job：`e2cecc09-d8f4-47d4-b71f-933f9b761223`；路线：`structured-query`；耗时：33250.0 ms；原回答规范 JSON SHA-256：`409631f82099f2597d4f8797305165fca3028bb9602a59ca63bfa64772aaef08`。

```text
按本次已识别条件找到 22 部，先展示 2 部。
1. 《电台司令: 彩虹里》；年份：2008；片长：43分钟；豆瓣评分：9.5；IMDb评分：8.7
2. 《放牛班的春天》；年份：2004；片长：97分钟；豆瓣评分：9.3；IMDb评分：7.9；TMDB评分：7.7
```

本次 queryPlan：

```json
{
  "intent": "search",
  "inheritContext": false,
  "genresAll": [
    "音乐"
  ],
  "yearFrom": 2000,
  "yearTo": 2010,
  "maxRuntime": 110,
  "limit": 2,
  "category": "mv",
  "actors": [],
  "actorsAny": [],
  "directors": [],
  "directorsAny": [],
  "excludedActors": [],
  "excludedDirectors": []
}
```

| 候选 ID | 名称 | 年份 | 片长 | 类型字段 | 导演字段 |
| --- | --- | --- | --- | --- | --- |
| 1MeM | 电台司令: 彩虹里 | 2008 | 43 | mv / 纪录,音乐 | David Barnard |
| gying_mv_X5Dz | 放牛班的春天 | 2004 | 97 | mv / 剧情,音乐,励志 | 克里斯托夫·巴哈蒂 |

## dev-v3-06 / 第 2 轮

请求：年份改成2015年及以后，其他条件不变，重新给两部。

Job：`d5ca7883-37f3-46df-8f93-cdb84e49e093`；路线：`structured-query`；耗时：10219.0 ms；原回答规范 JSON SHA-256：`59f070c322b1270e34a69b7f2de4d4ef316fb18f0262b3fd849d4149219bbb6c`。

```text
按本次已识别条件找到 47 部，先展示 2 部。
1. 《RAYE at the Royal Albert Hall》；年份：2024；片长：80分钟；豆瓣评分：9.5；IMDb评分：9.2
2. 《坂本龙一：杰作》；年份：2023；片长：103分钟；豆瓣评分：9.4；IMDb评分：8.0
```

本次 queryPlan：

```json
{
  "intent": "search",
  "inheritContext": false,
  "genresAll": [
    "音乐"
  ],
  "maxRuntime": 110,
  "limit": 2,
  "category": "mv",
  "actors": [],
  "actorsAny": [],
  "directors": [],
  "directorsAny": [],
  "excludedActors": [],
  "excludedDirectors": [],
  "yearFrom": 2015,
  "scope": "catalog"
}
```

| 候选 ID | 名称 | 年份 | 片长 | 类型字段 | 导演字段 |
| --- | --- | --- | --- | --- | --- |
| za3R | RAYE at the Royal Albert Hall | 2024 | 80 | mv / 音乐 | 保罗·达格代尔 |
| 3gLG | 坂本龙一：杰作 | 2023 | 103 | mv / 音乐,纪录 | 空音央 |

## dev-v3-06 / 第 3 轮

请求：我问的是最开始那份2000到2010年列表里的第一部，它的导演是谁？

Job：`049825a9-5bba-4024-b05c-b2dfa6370c13`；路线：`structured-query`；耗时：7703.0 ms；原回答规范 JSON SHA-256：`a304dc4578d9839f8d9fdcb7d873b4eb5163fb2db5c11c677a50a0a3f4488878`。

```text
《电台司令: 彩虹里》（2008）。本次片库返回导演：David Barnard。
这是Radiohead(电台司令乐队)2008年在他们地下室的录音棚里的现场录像，没有观众，有的只是乐队成员和制作人员。演出曲目为2007年专辑<In Rainbows>中的歌曲。
乐队介绍
英国的牛津不仅拥有世界著名学府，也是英国摇滚名团的盛产地之一，除了 RADIOHEAD，另外两个名噪一时的BRITPOP团体RIDE和SUPERGRASS也出身于此。RADIOHEAD的五位成员均是土生土长的牛津人，并且在风景如画的牛津近郊小镇 ABINGDON的私立男校里结识，开始了传奇生涯。 RADIOHEAD的灵魂人物，主音歌手兼吉他手THOM YORKE 八岁那年得到平生的第一件乐器：母亲送的一把西班牙吉他，两年后他便开始组团，随后加入了一支名为TNT的校园PUNK乐队。THOM第一次唱歌时便意识到他需要更加默契的合作乐手。很快他认识了 COLIN GREENWOOD，"因为我们年龄相仿并且常常在同一个派对结束时碰上"。出于对JOY DIVISION，THE SMITH，MAGAZINE以及怪异着装的喜爱，他们顺理成章地结成死党。另外一位校友ED O'BRIEN的加入是因为THOM认为他"很酷而且看上去象MORRISSEY"。举止温文尔雅的鼓手PHIL SELWAY随后取代了鼓机， 在一个星期五形成了最初的四人阵容。不久，COLIN的弟弟，爵士乐迷JONNY经过同哥哥及其朋友们的苦苦纠缠，终于作为口琴手加入进来。乐队在一个周末于牛津的JERICHO TAVERN（现已不存在）做了第一场表演，他们增加了一对演奏萨克斯风的姐妹，乐队的风格就象是年轻版的TALKING HEAD。 为了平
```

本次 queryPlan：

```json
{
  "intent": "details",
  "scope": "catalog",
  "inheritContext": false,
  "yearFrom": 2000,
  "yearTo": 2010,
  "movieId": "1MeM",
  "actors": [],
  "actorsAny": [],
  "directors": [],
  "directorsAny": [],
  "excludedActors": [],
  "excludedDirectors": []
}
```

| 候选 ID | 名称 | 年份 | 片长 | 类型字段 | 导演字段 |
| --- | --- | --- | --- | --- | --- |
| 1MeM | 电台司令: 彩虹里 | 2008 | 本次详情未返回 | mv / 纪录,音乐 | David Barnard |

## dev-v3-09 / 第 1 轮

请求：找一部电影，片长至少150分钟，同时不能超过100分钟。这两个都是硬条件；先告诉我条件能不能同时满足。

Job：`ccb50e63-6ae7-42e4-937a-a839dbc427cd`；路线：`structured-query`；耗时：18125.0 ms；原回答规范 JSON SHA-256：`0aedb2e9bd5fe52d2afd484637c5b27cf752aa667d8732b3596c5f0f459a0e74`。

```text
条件矛盾：片长不能同时至少150分钟且不超过100分钟，因为150 > 100，无法满足。 片长的最低要求超过最高限制，两个硬条件不能同时满足。请确认要修改哪一项。
```

本次 queryPlan：

```json
{
  "intent": "clarify",
  "semanticText": "片长至少150分钟；同时不能超过100分钟",
  "clarification": "条件矛盾：片长不能同时至少150分钟且不超过100分钟，因为150 > 100，无法满足。 片长的最低要求超过最高限制，两个硬条件不能同时满足。请确认要修改哪一项。",
  "inheritContext": false,
  "limit": 10,
  "category": "mv",
  "actors": [],
  "actorsAny": [],
  "directors": [],
  "directorsAny": [],
  "excludedActors": [],
  "excludedDirectors": []
}
```

第二轮 RAYE 的本次简介明确含“电视特辑节目”；第一轮 Radiohead 简介只称录音棚现场录像。电影身份问题另见 website-review.md，不能只用 mv 和音乐字段推断全部为音乐电影。

第三轮实际 movieId/detail ID 为第一轮首项 1MeM，而非最新列表首项 za3R。Same USER 的新 job 引用链已记录；跨用户、自然 900 秒到期、伪造历史隔离未在这四轮中另测。
