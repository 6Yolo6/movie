# Project Instructions

## Delivery workflow

- When the user requests a project code change, default to completing implementation, relevant tests, deployment of affected services, Git commit/push, and post-deployment verification. Do not ask the user to repeat "deploy and push" after the change is ready.
- A request limited to investigation, review, explanation, or planning does not authorize deployment or unrelated changes. Follow any narrower scope the user explicitly gives.
- Use the gying-project-ops skill for deployment and select the lightweight or full workflow below. Deploy only affected services, preserve available rollback images, verify real entry points, and keep unrelated working-tree changes out of commits.
- If a safety gate fails, tests fail, the deployment target is ambiguous, or an action is destructive or difficult to reverse, stop that action and explain the blocker. The default workflow does not authorize deleting data, rotating credentials, destructive migrations, force-pushing, or real external content publishing without the required confirmation.

## Lightweight delivery for small changes

- Small feature improvements on the existing deployment default to lightweight delivery when they require no SQL scripts/manual database changes and no schema, data, or persisted-state migration. Normal application database reads/writes do not by themselves require the full workflow.
- Implement, run relevant tests, build and deploy only affected services with the existing production Compose/configuration, briefly verify the affected behavior, real entry point and startup logs, then commit/push. Reuse already-passed checks unless new changes or failures justify rerunning them.
- Skip full ops snapshots/readiness, whole-stack baselines, complete source/artifact manifests, new encrypted checkpoints, SQL dumps, restore drills, full-stack verification and large evidence bundles. Keep the existing scheduled backup plan; do not expand backup coverage per small release. Read only the relevant status/configuration sections and record a short delivery result.
- Retain the previous image for rollback and update the existing host image pin if required, without the full host/ACL backup and audit procedure. Require an administrator session only when the affected action actually needs one.
- Use the full ops workflow for SQL/schema/data or incompatible persisted-state migrations, infrastructure or credential/security changes, and destructive or hard-to-reverse actions. Unrelated historical warnings and generic skill checklists do not automatically escalate a small change; explain a concrete new risk if escalation is necessary.

## Documentation and acceptance

- Small features and resolved bugs need only the relevant manual update and a short ledger entry: date, change/root cause, final validation, and necessary rollback or pending item. Usually 3–5 lines; do not create a long dated report or large evidence bundle by default.
- Perform one final acceptance of the affected behavior, real entry point, and startup logs. Reuse passed checks; add verification only for new changes, failures, or a concrete unresolved concern.
- Keep current values in the status manual, reusable behavior in topic manuals, and history in ledgers/Git. Preserve frozen evaluations, essential recovery evidence, and unresolved security, licensing, or migration limits. Documentation cleanup does not refresh observation dates or require deployment.

## Shell and file operations

- Prefer rg, fd, and git grep over recursive searches; exclude node_modules, bin, obj, dist, target, and .git unless explicitly needed.
- Prefer Python or Node.js for structured data and batch edits. Avoid apply_patch for large edits.
- Prefer native tools for the current environment. Use PowerShell for Windows-specific operations; use bash/native Linux tools in WSL/Linux unless Windows integration is required.
- Keep commands short and composable; avoid long or unnecessary shell pipelines.
