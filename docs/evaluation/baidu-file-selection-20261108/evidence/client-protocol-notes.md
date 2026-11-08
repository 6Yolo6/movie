# Baidu client protocol evidence — 2026-11-08

Scope: dedicated-account web session, share discovery/listing, selected whole-root transfer, exact destination verification, own-share creation/reconciliation, and small metadata upload. The implementation does not call an offline-download endpoint. All protocol inspection used public source reads; no real account login, transfer, share creation or site publishing was performed by this agent.

## Reference source

Repository: https://github.com/qjfoidnh/BaiduPCS-Go

Pinned commit: 1b9131817aaf8ca7dee24bc00e33ebc4c7a5cc73 (Apache-2.0). Protocol requests were implemented in Java; upstream code was not vendored. The companion client-upstream-references.json records each inspected file's URL, byte length and SHA256.

- baidupcs/prepare.go:99–114: GET /api/user/getinfo?need_selfinfo=1. baidupcs/baidupcs.go:154–165 and 475–525: records[0].uk and result.bdstoken response fields.
- baidupcs/prepare.go:220–228: GET /api/gettemplatevariable, fields=["bdstoken"]. Tokens are kept in memory.
- internal/pcscommand/transfer.go:53–92: share page, extraction verification, subsequent share listing with root/web/app_id/shorturl/channel. Verification is transient access state rather than a content transfer.
- baidupcs/transfer.go:28–95 and 131–177: fixed share endpoints, page metadata and fsidlist/path transfer form.
- baidupcs/prepare.go share methods: POST /share/pset private password share; GET /share/record and /share/surlinfoinrecord. baidupcs/share.go describes shareId/fsIds/typicalPath/status/expiredTime records.
- baidupcs/netdisksign/share_sign.go: share detail signature MD5(shareId + "_sharesurlinfo!@#").
- baidupcs/prepare.go:580–657: precreate, superfile2 single-block upload, create commit. baidupcs/baidupcs.go:108–114 and 420–434: fixed PCS host, app ID and endpoint path. baidupcs/util.go:26–33: rtype=2 skip policy; overwrite policy uses rtype=3.

## Implemented boundaries

The normal origin is fixed HTTPS pan.baidu.com. Public source URL normalization accepts only exact pan.baidu.com/yun.baidu.com share paths and 4-character extraction codes; it rejects userinfo, explicit ports, fragments and duplicate query parameters. The only additional host is fixed https://pcs.baidu.com/rest/2.0/pcs/superfile2 for a bounded metadata block. No returned upload host is followed. Redirects are never delegated to HttpClient; share-page redirects stay on the fixed normal origin. Cookie headers respect domain, path and expiry and never cross to an arbitrary host. A host-only pan login cookie cannot authorize the PCS upload branch.

Each HTTP response is bounded at 4 MiB. Directory/share lists require complete pagination within 20 pages of 100 items. Trees are bounded at 2000 items and depth 12, with safe names, strict parent paths for owned listings, cycle/duplicate checks and integer file sizes. Errors expose fixed operation codes rather than upstream response bodies, secrets or raw network causes.

Source plans contain URL/code, destination, owner/share IDs, root fsids and expected relative names/sizes/directory flags. They never contain cookies, bdstoken or sekey. To preserve hierarchy while honoring per-drive video/torrent switches, a whole root is selected only when every leaf is enabled and recognized; mixed forbidden roots are skipped. Share transfer does not flatten nested selections. Persisted plan policy is checked before recovery and writes. Destination success means exact names, sizes and directory structure, without partial or extra content.

The runner persists PREPARED and TRANSFER_SUBMITTING before sending a transfer, and SHARE_SUBMITTING before creating a share. Ambiguous outcomes keep those intents and recover by bounded reads only. Only an exception from a fresh actual transfer/share call that confirms rejection can retract that submission intent. Read failures during recovery or after an accepted POST never make the write retryable. Directory preparation uses rtype=2 and is idempotent; failure before transfer POST is known to be unsubmitted transfer work.

Own-share reconciliation requires a complete own-record scan. It matches exact destination and single directory fsid, skips definitely expired records, obtains password from the own-share details endpoint, verifies current account ownership and the page share ID against the record, and confirms accessible nonempty contents. Incomplete records or uncertain access checks fail rather than allowing an immediate new share. The own-share response is validated before ingestion.

Metadata upload accepts a nonempty .torrent at most 2 MiB or UTF-8 .txt at most 16 KiB containing a single valid v1 magnet xt; repeated tracker tr parameters are accepted. Existing and committed files must match exact path/name, size and MD5. rtype=2 and transfer ondup=fail prevent overwrite/rename. The full metadata-folder guard reads every bounded subtree before new sharing, unknown-result reconciliation, or reuse of an existing URL; it rejects videos, unknown file types, over-limit leaves, empty roots and empty directory leaves, while allowing older legal small metadata files. It never deletes contents.

The session vault stores AES-256-GCM encrypted state and a separate private key, with private atomic writes, bounded input, symlink/invalid-path rejection, account-replacement binding and an encrypted import-ticket ID. Metadata reads never generate keys. Expired saved cookies remain readable for account metadata/relogin binding; newly imported cookies must include a current pan-scoped BDUSS or BDUSS_BFESS. Cookie/session/envelope string representations are redacted. Production uses the private /app/data/baidu folder and fixed backend identity configured by deployment.

## Verification chronology

- tmp/baidu-client-fixture-112b920e4370: isolated immutable cached Maven image sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf; network none, no host ports or production mounts, user 10001, read-only root, capability drop, writable disposable source copy and read-only dependency cache. Main/test compilation passed; vault 9/9, client 16/16, P2P client 11/11 and P2P service 22/22 passed. Runner fixtures initially omitted the explicit video switch and consequently raised 409; this fixture issue was fixed.
- tmp/baidu-client-fixture-5931bc32d66a: compilation encountered a concurrent controller @Autowired import snapshot gap, already repaired by the root agent. No tests executed. The failed evidence remains immutable.
- tmp/baidu-client-fixture-8dc6181a9661: cancelled at root request to avoid parallel redundant compilation; owned container verified by full ID and labels, stopped and removed. No tests executed.
- tmp/query-boundaries-mysql-29840e919a35 (root full run): vault 9/9, client 19/19, login controller 4/4, login service 9/9 and real-MySQL Baidu task 8/8 passed. Runner 8/10 passed, with two test-fixture issues: restubbing a throwing mock with when(), and expecting ACTION_REQUIRED while the specified disabled-policy behavior preserves PENDING. Both fixtures were fixed with doThrow and PENDING/skipped assertions. Final client source adds two metadata-folder tests (21 total); root owns the final complete run and source-manifest matching.

No claim of real Baidu account interoperability is made from mocks. The dedicated-account QR login and bounded real read validation remain the user-facing activation path; production Baidu collection/transfer/sharing switches default off until configured.
