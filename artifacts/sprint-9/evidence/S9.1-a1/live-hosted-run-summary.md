# S9.1 live hosted extraction — conditions and observed result

Attempt `S9.1-a1`, 2026-11-04. Synthetic data only; the isolated evidence package was installed,
used, and removed; the owner's main package was never installed over, uninstalled or cleared.

## 1. Provider availability and authorization (the distinction requested)

- `.env` contains two credential **names** (`OPENCODE_GO_API_KEY`, `GOOGLE_AI_STUDIO_KEY`); names
  were inspected only and no value was printed, logged or written to any artifact. The file's
  mtime (Oct 25 20:55) shows it was not changed since the CAT1 runs, so no new credential has
  appeared for the profile that previously returned HTTP 401.
- The app ships both Google AI Studio routes as first-class presets
  (`app/src/main/java/io/github/triet4p/aegisf/provider/HostedProviderPresets.kt`:
  `google-gemma-4-26b-a4b-it`, `google-gemini-flash-lite`), alongside the OpenCode Go routes.
- The owner's recorded authority for hosted development trials covers Google AI Studio models
  with synthetic fixture data (`tools/slm/s8_hosted_request_config.json`,
  `providers.google-ai-studio.authentication` and `authority`: gemma-4-26b-a4b-it,
  gemini-3.5-flash-lite).
- CAT1's HTTP 401 was specifically the **OpenCode Go · DeepSeek V4.1 Flash** profile and
  `OPENCODE_GO_API_KEY` (`artifacts/hosted-category-cutover/reviews/CAT1-owner-revision-01.md:66`);
  at that time no other provider was tried because switching providers was outside that
  authorization, and the app had no saved provider profile
  (`artifacts/hosted-category-cutover/evidence/CAT1-a1/round2-verification.md`).
- Fresh evidence this attempt, without re-probing the recorded-401 OpenCode key:
  - the app's own real Gemini adapter completed a live two-turn tool-result exchange against
    `gemini-3.5-flash-lite` (`live-gemini-protocol-smoke.json`; `credential_recorded: false`,
    `synthetic_inputs_only: true`, served model reported as `gemini-3.5-flash-lite`);
  - the app's provider screen then validated and saved the same Google profile, and the
    Settings screen showed it **ACTIVE** (`device-live-run-ui.xml` header: `ACTIVE · Gemini API ·
    Gemini 3.5 Flash Lite · gemini-3.5-flash-lite`).
- Conclusion: a supported, explicitly configured, owner-authorized alternative was available and
  usable, so the live proof was run through it rather than being waived.

## 2. Live run

- Profile: `Gemini API · Gemini 3.5 Flash Lite` (endpoint
  `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent`),
  entered through the app's own provider screen; no custom endpoint and no hidden substitution.
- Synthetic capture saved through the app UI:
  `coffee 15000 vnd yesterday at 08:10 and taxi 40000 vnd on 2026-10-21 at 13:05`.
  Capture row: `capturedAtEpochMillis 1793755541667`, `captureTimezone Asia/Ho_Chi_Minh`.
- The Chat request (`INTERPRET EVENTS`, the only enabled workflow skill) ran one admitted run
  `run-f33950cda8beae403b22555bef96ed69` (`state SUCCEEDED`, `stopReason none`).
- The real tool sequence recorded in the app trace:
  `select_source` → `make_time_anchor` → `record_event` → `select_source` → `make_time_anchor`
  → `record_event` → one repeated `record_event` that the application answered without a second
  write.
- The first `select_source` result carried
  `relative_dates: [{text: "yesterday", day_offset: -1, ref: r_bbfc3de33346a3d5f855cd67}]`, and
  the model passed exactly that ref as `date_ref` to `make_time_anchor`. The host resolved it
  from the capture's own instant and zone — the model never stated the calendar date.

## 3. Persisted application state (pulled from the isolated app's database)

| Event | Span text (exact) | Reading | Amount | Date / time | Zone | Status |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `coffee 15000 vnd yesterday at 08:10` | Coffee purchase | 15 000 | 2026-11-03 08:10 | Asia/Ho_Chi_Minh | INTERPRETED |
| 2 | `taxi 40000 vnd on 2026-10-21 at 13:05` | Taxi ride | 40 000 | 2026-10-21 13:05 | Asia/Ho_Chi_Minh | INTERPRETED |

- 2026-11-03 is exactly the capture's own local day (2026-11-04) minus the stated "yesterday".
- `extractionVersion` is `agent-interface/5` on both rows; `unresolvedReason` is null;
  `merchant` is null on both (no counterparty was stated or invented).
- Ledger `transactions` = 0, `context_links` = 0, `pending_bank_drafts` = 0; operations are one
  `SAVE_CAPTURE` and two `UPDATE_EVENT` (the repeated third call produced no write).
- Full extract: `device-live-persisted-state.txt`.

## 4. Editable capture surface on the device

- The capture card shows the saved text, `Interpreted · not linked`,
  `Saved 2026-11-04 08:25 GMT+07:00 · Asia/Ho_Chi_Minh`, and `Manage events` / `Edit` / `Delete`
  (`device-live-capture-card.xml`).
- With the events expanded, each card shows its exact source excerpt, reading, amount and stated
  date/time, plus `Edit interpretation` and `Record manually`
  (`device-live-event-cards.xml`, `device-live-event-cards.png`). Exactly two cards exist.
- A user edit that clears derived readings is separately proven on the same device by
  `S91RelativeDayPhoneInstrumentedTest` (`aPeriodWordStaysAmbiguousAndTheSavedCaptureStaysEditable`).

## 5. Remaining limitation

The OpenCode Go · DeepSeek profile still has the recorded HTTP 401 credential and was not
re-probed. That profile is not the one this proof used; the supported Google profile above is.
