# Live Dashboard Root Reporter

This package replaces the Android foreground service with two small pieces:

- the Android APK is an LSPosed module scoped to `android` and `com.mi.health`;
- this KernelSU module runs the network/retry daemon outside the UI app.

The daemon reads `/data/adb/live_dashboard/config.json`, listens only on the
local abstract socket `live_dashboard`, and sends reports to the configured
dashboard. Configuration and queued health records survive module upgrades.

Build the daemon with Go and place it at
`module/bin/live-dashboard-daemon`, then zip the contents of `module/` (not the
directory itself). The GitHub Actions Android artifact performs these steps.
