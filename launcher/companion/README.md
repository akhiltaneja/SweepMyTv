# Sweep TV usage companion

See [PERSONALISATION.md](../PERSONALISATION.md) for data flow and usage.
Python 3, the signed-in Codex CLI and the workspace's ADB executable are required.
Run `--read-only` first to check access without touching the TV. The default command
syncs one sanitised snapshot; `--watch` continues at five-minute intervals.
Stop the watch process with Ctrl-C. Nothing is registered at login or installed
as a system service.
