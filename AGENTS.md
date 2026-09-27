# Project Instructions

## Delivery workflow

- When the user requests a project code change, default to completing implementation, relevant tests, deployment of affected services, Git commit/push, and post-deployment verification. Do not ask the user to repeat "deploy and push" after the change is ready.
- A request limited to investigation, review, explanation, or planning does not authorize deployment or unrelated changes. Follow any narrower scope the user explicitly gives.
- Use the gying-project-ops skill for deployment. Preserve rollback materials and verify real service entry points; deploy only affected services, and keep unrelated working-tree changes out of commits.
- If a safety gate fails, tests fail, the deployment target is ambiguous, or an action is destructive or difficult to reverse, stop that action and explain the blocker. The default workflow does not authorize deleting data, rotating credentials, destructive migrations, force-pushing, or real external content publishing without the required confirmation.

## Shell and file operations

- Prefer rg, fd, and git grep over recursive searches; exclude node_modules, bin, obj, dist, target, and .git unless explicitly needed.
- Prefer Python or Node.js for structured data and batch edits. Avoid apply_patch for large edits.
- Prefer native tools for the current environment. Use PowerShell for Windows-specific operations; use bash/native Linux tools in WSL/Linux unless Windows integration is required.
- Keep commands short and composable; avoid long or unnecessary shell pipelines.
