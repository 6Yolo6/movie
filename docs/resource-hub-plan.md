# GYing Resource Hub Plan

## Goals

Resource Hub extends the existing movie site without replacing the current data model.

- Store all movie metadata in `movie_metadata`.
- Store all usable resource links in `resource_link`.
- Prefer local database hits before external searches.
- Use TMDB for metadata and hot-list discovery.
- Use PanSou for resource discovery.
- Use quark-auto-save for transfer.
- Create an own Quark share before publishing an auto-collected resource.
- Keep manual submissions, audit, favorites, comments, and existing crawler data compatible.

## Current Data Rules

- `movie_metadata.popularity` is the local favorite count.
- `movie_metadata.tmdb_popularity` is the TMDB ranking score.
- `movie_metadata.tmdb_vote_average` is the TMDB user rating.
- TMDB data should not overwrite local favorite counts.
- New TMDB TV metadata should match existing series rows by `series_name`, title, or alias before creating a new `tmdb_*` row.
- Auto-discovered links should be marked with `resource_link.source = RESOURCE_HUB`.

## Resource Pipeline

1. Sync TMDB hot lists.
2. Upsert or match `movie_metadata`.
3. Skip discovery when the movie already has active resources, saved discoveries, or recent discovery tasks.
4. Search PanSou with title, original title, series name, and year.
5. Save discovery rows in `resource_discovery_result`.
6. Create Quark transfer tasks.
7. Run quark-auto-save.
8. Create an own Quark share for the saved folder.
9. Publish only rows with a usable own share URL into `resource_link`.
10. Mark movies without discovered resources as `TRAILER`.

## Manual Collection

Admin manual collection should accept a movie title or a movie ID.

- Exact ID match is used first.
- Exact title, original title, and series name matches are preferred.
- Fuzzy title and alias matches are fallback.
- `Run now` should execute discovery, submit transfers, and publish ready discoveries in one pipeline pass.

## Duplicate Control

Resource Hub should avoid duplicates at three levels.

- Metadata duplicate: match TMDB TV series to existing `series_name` rows.
- Discovery duplicate: avoid repeated original Quark URLs for the same canonical movie.
- Published duplicate: avoid repeated own share URLs or original URLs in `resource_link`.

Historical duplicates should be merged cautiously. Prefer moving tasks/resources to a canonical movie row and marking synthetic `tmdb_*` rows inactive instead of deleting data directly.

## Admin Observability

The admin page should show:

- TMDB configuration state.
- Worker state and batch limits.
- Pending tasks.
- Discovered resources.
- Pending Quark transfers.
- Saved discoveries waiting for publish.
- Failure reason and retry state.

## Bot Integration

QQ group and future OpenClaw channel bots should share the same backend orchestration.

- Query the local database first.
- If no resource exists, create a Resource Hub discovery pipeline task.
- Return an immediate acknowledgement.
- Send a later result message after transfer/share/publish completes.
- Avoid repeated transfer for the same movie and same source URL.

## External Services

- TMDB: metadata, trending, popular, top rated, upcoming.
- PanSou: resource search.
- quark-auto-save: transfer and account cookie management.
- Quark share API: create own share links from saved folders.
- NapCat: QQ group bot transport.
- OpenClaw: future QQ channel bot transport.
