# S9.9-a8 pre-Send state, staging and the one live workflow

Attempt **S9.9-a8** · runtime agent `S99PhoneRetry` (`gold-task`, full route) · review cycle
`S9.9-cycle-1` round 1 unused, zero assessed rounds.
Base `1cd84dd7a14aa4073b8abb74f11f6de180de8ab3` (`refs/heads/main`); worktree `F:/ai-ml/aegis-f`;
Git dir `F:/ai-ml/aegis-f/.git`.

Device ALT-LX2, serial `AAAJBB4930914141`; device date 2026-11-07 (Asia/Ho_Chi_Minh); IME
`com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`; screen
`off_timeout=2147483647`; `mWakefulness=Awake`; `mDreamingLockscreen=false`.

## 1. Pre-run state (read-only first)

- Source candidate unchanged: 32 paths, digest
  `3ef5cc29aece87357f3176e0415d5b2fcd158dff3abc422ea066d46bf790f574`, 32/32 hashes match; the
  measurement harness sha256 `6db5ec84837991f1e654759ebf6853c1c03c0d7f17accba6eb297cd2334d6979`.
- Installed isolated binaries equal to the local APK outputs (no rebuild/install): app
  `3e8e4b6a3fdf158db3edd7aaa2efdb083e2428e9749415ef81211b589df55c8e`, test
  `e69870838048067371c4b701d79e86f3baeba806db496614aa772afd2be3a264`.
- Provider prefs 65 B sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793` (no active profile);
  `files/s98_secret.env` absent.
- Read-only `fixture_audit` (`S99WorkflowPhoneInstrumentedTest#workflowEvidence -e s99_stage
  fixture_audit`) → `OK (1 test)`; log `pre-run-fixture-audit.log`: `pending=10`, categories
  `4:Food,1:Fuel,2:Fuel,3:Transport`, `activeCaptures=53` (a6 captures `96`/`97` present; old
  immutable evidence enumerated).

## 2. Private staging of the authorized non-Pro gateway target

- Credential source `.env` `OPENCODE_GO_API_KEY` (51 chars), copied over private stdin only to
  `files/s98_secret.env`: 72 B, host and device sha256 both
  `51d7e7e6abfebd77af5055f04443e1019cd4e98ae290d5322b94d9a7e8802378`; host copy deleted; the
  staging stage then deleted the device file (`secretFileDeleted=true`). No secret value was printed,
  passed in argv, logged or captured.
- `S98DeviceFixtureInstrumentedTest#stage -e s98_stage add_custom_profile -e s98_profile_id
  s99-opencode-go-flash -e s98_endpoint https://opencode.ai/zen/go/v1/chat/completions -e s98_model
  deepseek-v4.1-flash -e s98_protocol OPENAI_CHAT_COMPLETIONS -e s98_key_name OPENCODE_GO_API_KEY`
  → `OK (1 test)`; log `S98CONFIG custom id=s99-opencode-go-flash model=deepseek-v4.1-flash
  endpoint=https://opencode.ai/zen/go/v1/chat/completions protocol=OPENAI_CHAT_COMPLETIONS
  credentialNonBlank=true credentialMatch=true`. Then `-e s98_stage activate_profile` →
  `activated=s99-opencode-go-flash active=s99-opencode-go-flash`.
- Prefs 894 B sha256 `e6cc15b1783e36006c8d616f51817a4ae1a63af540cb523a0f6b1e2e0dcb9633`
  (`prefs-staged-encrypted.xml`); 0 raw-key substring hits; file holds `encrypted_profiles` +
  `encryption_key_alias`.
- No provider validation/probe was made; the only pre-Send provider steps were the two local
  no-network proofs below.

## 3. Local no-network proofs (fresh)

- `#guardPathsProof` → `OK (1 test)`; `guard-paths.log`:
  `S99M guardProof allowedPostForwarded=true allowedStreamForwarded=true modelPostBlocked=true
  modelStreamBlocked=true endpointPostBlocked=true endpointStreamBlocked=true postCalls=1
  streamCalls=1 checks=6 allowedPostStatus=200 allowedStreamStatus=200`.
- `#modelProof` → `OK (1 test)`; `model-proof.log`:
  `profile id=s99-opencode-go-flash model=deepseek-v4.1-flash protocol=OPENAI_CHAT_COMPLETIONS
  endpoint=https://opencode.ai/zen/go/v1/chat/completions maxOutputTokens=null reasoning=null`;
  `serializedModel=deepseek-v4.1-flash wireMatches=true limitFields=[]`; `installed=true
  expectedModel=deepseek-v4.1-flash expectedEndpoint=https://opencode.ai/zen/go/v1/chat/completions
  streamingCapable=true`; `modelProof OK guardChecks=0`.

## 4. The one authorized live workflow

`#measurementPair -e s99_measure pair -e s99_pin_model deepseek-v4.1-flash -e s99_pin_endpoint
https://opencode.ai/zen/go/v1/chat/completions` — one invocation, one Send, normal terminal path:
`OK (1 test)`, `Time: 220,692`; harness log `measure-pair-device.log`.

- Run `run-25d64b8a049384d79e88ad356a1661ba`, scope `chat-78c156e6-4827-467d-ad3a-b1aea2624b53`,
  terminal `SUCCEEDED / none`; **10 HTTP requests, all 200**; 2 attempts (7 + 3 turns); 17 tool calls;
  1 clarification round; `guardChecks=10`; hosted wait 196,423 ms; trace exported before cleanup
  (5,159 rows / 3,672,324 B).
- No `update_draft_review` call: the model declined to map "Synthetic Pharmacy"/"Synthetic Bookshop"
  onto Food/Fuel/Transport and asked which category to use; the harness reply answered draft
  ownership instead. Harness then stopped:
  `OUTCOME=FAILURE_CATEGORY_WRITES appliedVerifiedWrites=0 required=2`; no confirmation, no manual
  category, no assisted ledger/dashboard.
- Full request/tool/clarification/masking tables: `trace-analysis.md`.

## 5. Manual comparator on the same fixture (fresh a8)

- `path=manual metrics actions=18 chars=91 captureToSavedMillis=12688 firstSavedMillis=7073
  finalizeMillis=132 forms=2 fieldsPerForm=4 categoryChoices=2 descriptions=51`.
- Records `29:54000:4` + `28:89000:3`; day query aggregate **143,000 / 2** (Transport 89,000, Food
  54,000); visible dashboard (period 11/01–30) **183,000 / 4**; stored ledger 4 records / **183,000**
  = `29:54000, 28:89000, 2:20000, 1:20000`; exact-id cleanup `Applied/Applied` → 0/0.

## 6. Cleanup (after the trace was preserved)

- Harness: drafts `68`/`69` dismissed `Applied`; note capture `101` deleted `Applied`;
  `afterCleanup spending=0 records=0` (`aborted=HOSTED_CATEGORY_WRITES_MISSING`).
- `#stage remove_profile -e s98_profile_id s99-opencode-go-flash` → `OK (1 test)`;
  `S98CONFIG removed=s99-opencode-go-flash remaining=[] active=null`.
- Prefs back to 65 B `3325d2a8…`; `files/s98_secret.env` absent.
- Post-run read-only `fixture_audit`: pending 10, categories unchanged, active captures 55 (new
  immutable `99`/`100` disclosed, preserved); `run_audit`: row 26 `SUCCEEDED|none`, counts
  `PROVIDER_REQUEST=10`, `TOOL_CALL=17`, `TOOL_RESULT=17`, `ATTEMPT_STARTED=2`,
  `ATTEMPT_OUTCOME=2`, `CLARIFICATION_REQUEST/REPLY=1`.

## 7. Correlation and limits

- Run `run-25d64b8a…` ↔ note capture `101` ↔ seeded drafts `68`/`69` ↔ events `46`/`47` ↔ linked
  context #68→#46 and #69→#47 ↔ manual records `28`/`29` ↔ new immutable bank evidence `99`/`100`.
- One `busy=true`/`busy=false` pair = one Send; no force-stop, no DB pull/polling mid-run, no
  network toggle, no edit after Send; no second workflow.
- B10 is **UNMET**: no HOSTED-resolved category, no applied+verified `update_draft_review`, no two
  explicit confirmations, no assisted ledger/dashboard endpoint.
