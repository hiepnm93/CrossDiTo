# CrossDiTo Phone Companion — test build 0.2

Fixes over 0.1:

- **Fixed: app crashed immediately on launch** — the activity theme was not
  an AppCompat theme (required by AppCompatActivity), throwing
  IllegalStateException in onCreate before any UI appeared.
- Added Sentry crash reporting: crashes and ANRs are now reported to the
  project's Sentry dashboard automatically, so future failures arrive with
  full stack traces without needing logcat.
- Added the INTERNET permission (needed by Sentry and the Open-Meteo fetch).

Assets: `apk/CrossDiTo-Companion-debug.apk` (install over the old one),
`firmware/` unchanged from 0.1 but rebuilt for completeness.

Source: `feat/phone-companion` branch. Test procedure: see release 0.1.
