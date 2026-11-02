# Earlier failed candidate — a19 device-preflight attempt 1 (debug proof)

Preserved as measured; nothing here was rewritten, repaired, or reused as a passing candidate.

- **when:** 2026-10-29, before the recorder fix; superseded by the second device-preflight attempt
- **test APK:** `ad3eed372a00d3d84e8505e29db397eb1a91e70887b92d0a02db2067813b4671`
  (build record copied here as `../build-before-recorder-fix.json`)
- **host record:** `device-preflight-host.json` — `instrumentation_status: preflight_failed`
  - `S813LayaProjectionSmokeInstrumentedTest` → `OK (1 test)`
  - `S813V3QualificationPreflightInstrumentedTest` → `OK (1 test)`
  - `S813DebugCaptureInstrumentedTest` → `instrumentation_failed` (exit 0, 2 tests run, 1 failure)
- **observed failure:** `org.json.JSONException: Not a primitive array: class
  kotlin.sequences.DistinctSequence` at `S813DebugCapture.tokenProvenance` — the recorder passed a
  `Sequence` to `org.json.JSONArray`.
- **also preserved:** `device-preflight/` (domain, Laya smoke, partial proof), `path-identity-preflight.json`,
  and `preflight-static-before-fix.json` (the pre-fix source manifest binding).

The fix was a surgical change in `S813DebugCapture.kt` (materialize the token lists); the retained
second attempt (`snapshot-26/device-preflight-host.json`, `OK (3 classes; 4 tests)`) is the one the
freeze binds. This directory is evidence, not an input to any command.
