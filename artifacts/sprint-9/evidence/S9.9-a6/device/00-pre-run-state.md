# S9.9-a6 device state, provider target and the one hosted workflow

Attempt **S9.9-a6** · runtime agent `S99GoPhoneFinish` (`gold-task`, full route) · review cycle
`S9.9-cycle-1` round 1 unused, zero assessed rounds.
Base `1cd84dd7a14aa4073b8abb74f11f6de180de8ab3` (`refs/heads/main`); worktree `F:/ai-ml/aegis-f`;
Git dir `F:/ai-ml/aegis-f/.git`.

Device ALT-LX2, serial `AAAJBB4930914141`; device date 2026-11-07 15:43 +07 (Asia/Ho_Chi_Minh);
IME `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` (unchanged);
`screen_off_timeout=2147483647`; `mWakefulness=Awake`; `mDreamingLockscreen=false`.

## 1. a5 temporary-profile cleanup (performed first, per handoff)

- Before: `shared_prefs/hosted_provider_settings.xml` 866 B sha256
  `9ed0db47bc984bf15c33a0132dfe5c1b90b80a9e563c2b149921b0a898ccf814` (a5's staged
  `s99-direct-deepseek-flash`).
- Invoked the existing production-method stage:
  `S98DeviceFixtureInstrumentedTest#stage -e s98_stage remove_profile -e s98_profile_id
  s99-direct-deepseek-flash` → `OK (1 test)`;
  log `S98CONFIG removed=s99-direct-deepseek-flash remaining=[] active=null`.
- After: 65 B sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793` — the disclosed pre-run
  baseline. The original three isolated profiles lost in a3 remain lost and are **not** restored.

## 2. Read-only fixture audit before the attempt

`S99WorkflowPhoneInstrumentedTest#workflowEvidence -e s99_stage fixture_audit` → `OK (1 test)`
(log `pre-run-fixture-audit.log`):

- `pending=10`; categories `4:Food,1:Fuel,2:Fuel,3:Transport`; `activeCaptures=51`.
- a5's immutable bank-evidence captures `93`/`94` are present (now enumerated, closing a5's gap):
  both `BANK_EVIDENCE_REF`, texts `Synthetic Pharmacy` / `Synthetic Bookshop`.
- Older immutable evidence `80,81,82,85,87,88,90,91` still present; draft ids 60–63 are
  `DISMISSED`; no pending 89,000/54,000 draft.

## 3. Authorized non-Pro gateway target restored from cited prior evidence

Cited prior working configuration (S8.13 records and the `opencode-go-deepseek` preset): protocol
`OPENAI_CHAT_COMPLETIONS`, endpoint `https://opencode.ai/zen/go/v1/chat/completions`, model
`deepseek-v4.1-flash` (non-Pro), provider-default output (no cap field).

- Credential source `.env` `OPENCODE_GO_API_KEY` (name inspected; value length 51, single line).
  Transferred over private stdin only:
  `awk '^OPENCODE_GO_API_KEY=' .env | adb shell "run-as … cat > files/s98_secret.env"`.
  Device file 72 B; host and device sha256 both
  `51d7e7e6abfebd77af5055f04443e1019cd4e98ae290d5322b94d9a7e8802378`; staging deleted it
  (`secretFileDeleted=true`). No secret value was printed, passed in argv or written to evidence.
- `S98DeviceFixtureInstrumentedTest#stage -e s98_stage add_custom_profile -e s98_profile_id
  s99-opencode-go-flash -e s98_endpoint https://opencode.ai/zen/go/v1/chat/completions
  -e s98_model deepseek-v4.1-flash -e s98_protocol OPENAI_CHAT_COMPLETIONS
  -e s98_key_name OPENCODE_GO_API_KEY` → `OK (1 test)`;
  `S98CONFIG custom id=s99-opencode-go-flash model=deepseek-v4.1-flash
  endpoint=https://opencode.ai/zen/go/v1/chat/completions protocol=OPENAI_CHAT_COMPLETIONS
  credentialNonBlank=true credentialMatch=true`, then
  `-e s98_stage activate_profile` → `activated=s99-opencode-go-flash active=s99-opencode-go-flash`.
- Staged prefs 894 B sha256 `9f8961c7885b91c7177f452246173c899c9b770f1711ac896d9f2119f12eee5a`;
  raw key substring hits in that file: **0**; file holds `encrypted_profiles` +
  `encryption_key_alias` (`prefs-staged-encrypted.xml`).
- No provider validation/probe was made. The only pre-Send provider steps are local, no-network
  proofs (sections 5–6).

## 4. Test-only harness change (only source edit)

`S99MeasurementInstrumentedTest.kt` (androidTest only; main source untouched):

- The expected target is no longer compiled in: it is required from
  `-e s99_pin_model` / `-e s99_pin_endpoint`, and a missing pin fails closed (no default, no
  fallback). A model containing `pro` is refused at pin time.
- The per-request guard now checks **both** the serialized model and the request URL before either
  `post` or `postStream` reaches its delegate.
- Removed the two hardcoded expectation constants; guard/profile KDoc updated.

Build (final affected check): `:app:assembleDebug -P…=io.github.triet4p.aegisf.s812evidence
:app:assembleDebugAndroidTest` → **BUILD SUCCESSFUL**.
APKs: app `3e8e4b6a3fdf158db3edd7aaa2efdb083e2428e9749415ef81211b589df55c8e` (unchanged from a5;
main source unchanged), test `e69870838048067371c4b701d79e86f3baeba806db496614aa772afd2be3a264`.
Device hashes of both packages equal those local outputs.

Candidate snapshot after the edit: 32 paths, manifest
`artifacts/sprint-9/evidence/S9.9-a6/candidate-manifest.txt`, digest
`3ef5cc29aece87357f3176e0415d5b2fcd158dff3abc422ea066d46bf790f574` (only line 9, the measurement
test, differs from a5's `7d0cf80b…`).

## 5. Fake-delegate guard proof (no network)

`S99MeasurementInstrumentedTest#guardPathsProof -e s99_measure guard -e s99_pin_model
deepseek-v4.1-flash -e s99_pin_endpoint https://opencode.ai/zen/go/v1/chat/completions` →
`OK (1 test)`:

```
S99M guardProof allowedPostForwarded=true allowedStreamForwarded=true modelPostBlocked=true
modelStreamBlocked=true endpointPostBlocked=true endpointStreamBlocked=true postCalls=1
streamCalls=1 checks=6 allowedPostStatus=200 allowedStreamStatus=200
```

The allowed payload was forwarded once per path; a wrong model and a wrong endpoint were both
refused before either delegate (postCalls/streamCalls stayed 1).

## 6. Model proof (local, no network)

`#modelProof -e s99_measure model` with the same pin → `OK (1 test)`:

```
S99M guard profile id=s99-opencode-go-flash model=deepseek-v4.1-flash
protocol=OPENAI_CHAT_COMPLETIONS endpoint=https://opencode.ai/zen/go/v1/chat/completions
maxOutputTokens=null reasoning=null
S99M guard serializedModel=deepseek-v4.1-flash wireMatches=true limitFields=[]
maxOutputTokens=null
S99M guard installed=true expectedModel=deepseek-v4.1-flash
expectedEndpoint=https://opencode.ai/zen/go/v1/chat/completions streamingCapable=true
S99M modelProof OK guardChecks=0
```

## 7. The one authorized hosted workflow — FAILED at the gateway (429 overload)

`#measurementPair -e s99_measure pair` (same pin), one invocation, normal terminal path:
`OK (1 test)`, `Time: 40,644`. Run `run-40c64cb9bc0419251ba5f6fb32e899b1` (scope
`chat-…`, run row shown as `40c64cb9…e899b1`). Full sanitized trace exported to the device
**before cleanup** (`rows=19`, 226,371 B) and pulled to `run-40c64cb9….trace.json`.

| attempt | request at (+07) | HTTP | gateway body | x-zen-model | retry-after |
|---|---|---|---|---|---|
| 1 | 15:44:28.224 | **429** | `{"error":{"type":"server_error","message":"Upstream request failed: [overloaded] the server is overloaded; retry shortly"}}` | `deepseek-v4.1-flash` | 2 s |
| 2 | 15:44:35.013 | **429** | same message (`x-opencode-log-id 01a1b52e-5da3-…`) | `deepseek-v4.1-flash` | 3 s |
| 3 | 15:44:43.050 | **429** | same message (`x-opencode-log-id 01a1b52e-7d06-…`) | `deepseek-v4.1-flash` | 2 s |

All three requests were serialized by the production adapter with `model=deepseek-v4.1-flash`,
URL `https://opencode.ai/zen/go/v1/chat/completions`, 10 tools, 2 messages, `limitFields=[]`
(provider default, no cap), `Authorization` in `redactedHeaders` (value absent). The pinned guard
checked all three (`guardChecks=3`). Terminal: attempt outcomes `FAILED / PROVIDER_FAILURE`,
`detail=rate_limited:429`, `turns=0`, `toolCalls=0`; run terminal at 15:44:45.785, hosted wait
18,896 ms, 0 clarification rounds, 0 tool calls, 0 applied/verified hosted category writes.

Because 0 of the 2 required hosted writes were applied+verified, the harness correctly stopped the
assisted path before any manual category/confirmation action:
`OUTCOME=FAILURE_CATEGORY_WRITES appliedVerifiedWrites=0 required=2`, then exact-id cleanup.
No second live workflow and no further provider request was made.

## 8. Manual comparator on the same fixture (fresh a6)

Manual path reached its endpoint before the assisted attempt (`day=2026-11-07`):

- actions **18**, characters **91**, first save 6,573 ms, capture→second save **11,684 ms**,
  finalize 134 ms, 2 forms × 4 fields, 2 category choices, 51 description characters.
- records `26:89000:3` (Transport) and `27:54000:4` (Food); dashboard **143,000 VND / 2 records**;
  stored ledger **4 records / 183,000** = `27:54000, 26:89000, 2:20000, 1:20000` (foreign 40,000
  baseline preserved and included in the period total).
- exact-id cleanup `Applied/Applied`; day aggregate back to `0/0`.

## 9. Cleanup after the attempt

- Harness: draft `66` and `67` dismissed `Applied`; note capture `98` deleted `Applied`; assisted
  ledger none; daily aggregate `0/0` (`aborted=HOSTED_CATEGORY_WRITES_MISSING`).
- `S98#stage remove_profile -e s98_profile_id s99-opencode-go-flash` → `OK (1 test)`;
  `S98CONFIG removed=s99-opencode-go-flash remaining=[] active=null`.
- Prefs after cleanup: 65 B sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793` — baseline restored.
  `files/s98_secret.env` absent.
- Post-run read-only `fixture_audit`: `pending=10`, categories unchanged, `activeCaptures=53`
  (`post-run-fixture-audit.log`). The two new captures are this attempt's immutable bank evidence:
  `96` and `97` (`BANK_EVIDENCE_REF`, `Synthetic Pharmacy` / `Synthetic Bookshop`, captured at
  1794041061898 / 1794041061949). Both are preserved; production delete refuses them by design.
  No draft, note capture or ledger residue remains.
- Post-run read-only `run_audit` (`post-run-audit.log`): row 25 is
  `run-40c64cb9bc0419251ba5f6fb32e899b1|FAILED|PROVIDER_FAILURE`, with trace counts
  `PROVIDER_REQUEST=3`, `PROVIDER_FAILURE=3`, `ATTEMPT_OUTCOME=3`. Terminal row, no orphan and no
  cancellation needed.

## 10. Correlation, side effects, limits

- Run `run-40c64cb9bc0419251ba5f6fb32e899b1` ↔ note capture `98` ↔ seeded drafts `66`/`67` ↔
  manual records `26`/`27` (created and deleted in the same test) ↔ immutable bank evidence
  `96`/`97`.
- **Disclosed side effect:** the first build in this attempt was the default variant, so its
  androidTest APK `41d5bd47500a3739036d26b0136164dfca59f42457bd86b6defea4f45aec867f` was installed to
  the **main app's test package** `io.github.triet4p.aegisf.test` at 15:41:30. That package is a
  test-only package; the owner main app `io.github.triet4p.aegisf` was untouched
  (`f42d1cf82c6d2f27e90f1a8a59b486e58e26c46e8b9b2cad3fab2bf049c3c8c7`, lastUpdateTime
  2026-11-05 10:16:52, versionCode 4), and no main-app data was cleared or written. The correct
  isolated variant was rebuilt and installed afterwards (`.s812evidence` app `3e8e4b6a…`, test
  `e6987083…`).
- No mid-run interference: one `am instrument` call waited for its own terminal completion; no
  force-stop, no DB pull/polling during the run, no network/airplane toggle, no edit after Send.
- B10 is **UNMET**: no HOSTED-resolved categories, no two explicit confirmations, no assisted
  ledger/dashboard endpoint.
