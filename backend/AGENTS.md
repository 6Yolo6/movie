# Backend Agent Notes

- Work from `D:\lide_expert_manage\gying-movie\backend` for backend-only commands.
- Use Java 17 and Maven.
- Before committing backend changes, run `mvn -q -DskipTests compile`.
- Keep Resource Hub ingestion idempotent: prefer existing `movie_metadata` and `resource_link` rows over creating duplicates.
- Do not commit real secrets, cookies, tokens, or local `.env` values.
- For Resource Hub Quark flow, publish to `resource_link` only after a usable own share URL exists.
