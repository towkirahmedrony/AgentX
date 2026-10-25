# Device execution and privileges

## Four independent questions

1. Is a service installed/running and its command runner connected?
2. Which UID did the harness verify for that transport?
3. Does this chat/project currently permit the requested root use, and is privileged access paused?
4. Which route will this command actually select for its workspace and `access` argument?

The live context reports a snapshot of these separately. The executor rechecks identity and permission at dispatch and records an execution receipt. Connection readiness is not a guarantee that a later command succeeded. Do not claim a Shizuku or root execution from an app-UID result.

## Access modes

- **Normal:** app UID only; it does not request root or Shizuku.
- **Auto:** for non-app workspaces, tries an allowed, ready direct-root service, then Shizuku, then the app route. Ordinary app-owned workspace work stays on the app UID.
- **Shizuku:** prefers Shizuku; fallback follows the user's setting.
- **Root:** prefers the allowed direct-root service; fallback follows the user's setting.
- **Both:** prefers Shizuku for ordinary device work and supports explicit root actions in the same task. It does not mean either connection is actually ready.

`shell` and `shell_background` support `access="auto"`, `"shizuku"` or `"root"` as specified by their schemas. Explicit `shizuku` pins that transport and never silently falls back to direct root or the app UID. Explicit `root` requires UID 0 and an allowed root use; it does not degrade to a non-root command. Never bypass this with `su` or `sudo`. Android access overrides are not meaningful on an SSH host and are rejected there.

## Shizuku readiness

- Not installed: no Shizuku transport.
- Installed but stopped: the user must start it.
- Running without permission: AgentX needs the user's Shizuku authorization.
- Permission granted while binding, unbound or failed: the privileged runner is not ready.
- A bound ready runner or compatibility-ready runner is callable. Compatibility mode is an alternative connection backend; it does not confer more permission.

Shizuku normally runs as Android's shell UID 2000. A root-started Shizuku server may run as UID 0. Its authorization is separate from direct root. The snapshot may say its UID has not yet been checked; dispatch verifies the actual UID. Do not infer a UID from the server's installation or the presence of `su`.

For Android system actions, use the supported command with `access="shizuku"` when requested/needed. App-workspace development otherwise normally uses the app user. A shell-identity result and a UID 0 result have different capabilities. Some OS operations require a running UI, Android permissions or SELinux access even with a privileged runner; inspect the real error.

## Direct root

A detected Magisk/KernelSU/APatch-family manager is not a root grant. Root is ready only after a connected service verified UID 0. Temporary root mode additionally scopes use to allowed projects and the active app session, current chat or next action. A next-action allowance is consumed by the action, not by reading this context. With temporary mode off, a live permitted root connection is usable across projects; it still respects pause and connection checks.

The prompt does not request root, wake a grant dialog or consume an allowance. Ask for a user-controlled grant only if the user's task actually requires unavailable privilege. When neither privileged transport is ready, normal app development and file access can still work within Android's storage permissions.

## Storage and dispatch failures

The shell UID cannot enter app-private data just because Shizuku is connected. Android's shared storage normally cannot host executable native tools or symlinks. The harness owns separate app and shell Linux prefixes and scratch directories; use the active tier's returned environment.

Fallback can occur before dispatch when a route cannot start. A possibly dispatched command is never automatically replayed on another transport. After a connection loss, check the process, files or remote operation's state before retrying a mutation. Do not work around an explicit transport request by changing the whole access mode.
