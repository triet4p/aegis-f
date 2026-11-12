# S10.8 attempt a1 — round-2 verification record

Candidate: the uncommitted working tree at base `272ad24e9c79b0811f881109929bb0ccd37837d9`
(see `source-snapshot.txt`); isolated package `io.github.triet4p.aegisf.s108evidence`. No commit,
staging, push or checkpoint. Owner package `io.github.triet4p.aegisf`, its data and pending drafts
were never cleared, instrumented or replaced. All app writes below are in the isolated package or in
in-memory test databases.

**Operative S10.8 sentences (quoted verbatim from the live plan, 2026-11-11):**

> Focused settings children cover existing provider/protocol/profile/endpoint/model/key/output controls, skills/history, notification permissions/allowed apps/intake diagnostics and privacy/retention. Routine actions, advanced options and destructive confirmations remain distinct. Never expose secrets, debug enums or raw operational panels as everyday UI.

> Design-to-existing-capability mapping: Rules expose the existing normalized exact-description matcher; wildcard examples in screen-specification §10 are illustrative, not authorization to add a backend prefix matcher. Preserve the sprint's existing-command constraint and label matching truthfully. Native verification must not change radios, connectivity or other device system settings without explicit owner permission; safe navigation to system destinations must not grant/revoke access.

> Acceptance / phone proof: reach and exercise every supported secondary action. All categories, including seeded defaults, remain user-mutable subject to reference safety; archive preserves history, active references block unsafe deletion, and suggestion origin does not imply catalog ownership/locking. Cover empty lists, name collision/validation, loading/save/test errors, stale/discarded edits and offline local configuration. Preserve endpoint disclosure, masking/consent and secret handling; provider tests/intake actions obey existing authorization/device restrictions. Unsupported backup/release functions remain Sprint 11 obligations, not fake working controls.

## 1. Host build and focused tests

```
./gradlew.bat --no-daemon :app:testDebugUnitTest \
  --tests "io.github.triet4p.aegisf.data.CategoryDeletionTest" \
  --tests "io.github.triet4p.aegisf.data.CategoryCommandServiceTest" \
  --tests "io.github.triet4p.aegisf.data.CategoryRuleQueryTest" \
  --tests "io.github.triet4p.aegisf.data.LedgerRepositoryRejectionTest" \
  --tests "io.github.triet4p.aegisf.data.WorkflowQueryServiceTest" \
  --tests "io.github.triet4p.aegisf.data.FinancialCommandServiceTest" \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -PS108_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s108evidence --console=plain
```

Result: **BUILD SUCCESSFUL**. JUnit XML: `CategoryDeletionTest` 6/6, `CategoryCommandServiceTest`
8/8, `CategoryRuleQueryTest` 2/2, `LedgerRepositoryRejectionTest` 12/12,
`WorkflowQueryServiceTest` 20/20, `FinancialCommandServiceTest` 28/28 — **76 focused host tests,
0 failures/errors/skips**. The updated deletion tests now assert the reference-blocked result and
unchanged records; the command test asserts the structured `Conflict` with exact reference counts
and an unreferenced-delete success.

Final debug/androidTest APKs were built with the isolated application id; SHA-256 values are in
`apk-sha256.txt`.

## 2. Native instrumented run

Device HONOR ALT-LX2 (`ALT_LX2`), Android 14 / API 34, 720 × 1610 px, 320 dpi.

```
adb shell am instrument -w \
  -e class "io.github.triet4p.aegisf.ui.S108SettingsInstrumentedTest" \
  io.github.triet4p.aegisf.s108evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (14 tests)** in 84.4 s on the round-2 frozen candidate. Raw output:
`logs/instrumentation-final-round2d.txt`. Prior correction runs are retained
(`instrumentation-round2-corrections.txt` = 14/14, `instrumentation-final-round2*.txt`); round-1
failures/corrections remain in the earlier logs.

### 2.1 Categories: add, collision, rename, archive, reference-blocked delete
(`categoriesAddRenameArchiveAndDeleteWithReferenceSafety`)

Fixture: Food (referenced by one 45,000 ₫ record) and Transport. Before: spending **45,000 ₫**.

- Collision `Food` is refused with the typed name intact; nothing stored.
- `Pets` is added, renamed `Pets & Vet`, archived. Archived filter shows the badge `Archived` plus
  the separate caption `History kept`; the test asserts the doubled phrase
  `Archived · history kept` is absent. Capture `s108-03-categories-archived-en`.
- Delete of referenced `Food` shows **This category is still in use**, names
  **1 stored transaction uses this category**, has **no `Delete permanently` button**, and offers
  **Archive instead**. The record still holds exactly 45,000 ₫ and category Food; spending remains
  45,000 ₫. Capture `s108-04-delete-impact-en`.
- Archive instead keeps the record filed under the now-archived Food and leaves the total at
  45,000 ₫.
- The unreferenced Transport category still deletes through the same UI: it shows
  **Nothing stored uses this category**, deletes permanently, and the row/total checks pass.
- The API-level delete is also blocked (`CategoryDeletionResult.Blocked`) and leaves the record,
  rule and draft untouched.

### 2.2 Delete re-checks references inside the transaction
(`categoryDeleteRechecksReferencesInsideTheTransaction`)

- Open the delete dialog for the unreferenced `Temporary`: **Nothing stored uses this category**
  (`s108-04b-delete-unreferenced-en`).
- While the dialog is open, add a 23,000 ₫ record referencing Temporary, then press
  `Delete permanently`.
- The command/DAO transaction re-checks all three reference classes, refuses, and the dialog
  re-reads the impact: **This category is still in use**, **permanent deletion is blocked**, no
  destructive button, Archive instead offered. Category and record remain, total 23,000 ₫.
  Capture `s108-04c-delete-race-blocked-en`.
- This is the race-safe proof: counts and delete are one `@Transaction` in
  `CategoryDao.deleteIfUnreferenced`; `WorkflowCommandService.deleteCategory` returns `Conflict`.

### 2.3 Rules: list, validation, add, change, remove, stale category
(`rulesAddChangeAndRemoveExactDescriptionRules`, `ruleEditRefusesACategoryArchivedWhileTheSheetIsOpen`)

- Rules tab shows stored `circle k → Food`; blank description is refused; adding
  `Grab ride → Transport` stores `grab ride`; duplicate is refused; changing to Food updates the
  rule; remove confirmation removes it. Capture `s108-05-rules-en`.
- A rule edit whose category is archived while the sheet is open shows
  **That category is no longer available.**; the cached archived choice is dropped, no active
  category is left, and Save is disabled. Rule data is unchanged. Capture
  `s108-17-rule-stale-category-en`.
- **Specification mapping (F3):** screen-specification §10's `HIGHLANDS*` examples are illustrative.
  The stored rule is one normalized exact description (`RememberedCategoryRuleEntity.patternKey`,
  unique; `rememberedCategoryRuleKey` lowercases/collapses whitespace; no prefix/wildcard column or
  command exists). The plan's live line 71 now records this mapping; the UI labels matching
  truthfully and the README says so.

### 2.4 Settings hub and focused children
(`settingsHubIsANavigationHubAndEachRowOpensItsChild`)

Hub groups are the only content; `Credential`/`HTTPS endpoint` are absent; each labelled row fires
its callback; language/appearance sheets select `Tiếng Việt`/`Dark`. Capture
`s108-06-settings-hub-en`.

### 2.5 Bank notifications and safe system destinations
(`bankSettingsShowsDiagnosticsAndManagesTheAllowlist`,
`systemDestinationsResolveAndReconnectRefusesWithoutAccess`)

- Status group: Granted/listener/allowlist/pending count. `MB Bank` refused; `com.mb.mobile` added,
  confirmed-removed; existing package retained; refresh re-reads. Captures `s108-07`, `-07b`.
- The notification-access and app-info intents both resolve from the installed package
  (`PackageManager.resolveActivity`).
- `requestListenerRebind(context, accessGranted = false)` returns `RefusedNoAccess` and the
  injected rebinder is never called — the state-changing reconnect path is not invoked.
- Real shell launched both system destinations and returned with Back:
  `device-system-notification-access.png`, `device-system-app-info.png`. No permission was
  granted/revoked, no radio/system setting changed.

### 2.6 Provider profile: protocol/preset/key/output, test, save, use, delete
(`providerProfileIsTestedSavedActivatedAndDeletedWithoutExposingTheCredential`)

A deterministic in-process `HttpTransport` returns a valid OpenAI-compatible turn; no network call.

- Select OpenAI-compatible protocol, DeepSeek preset, name + synthetic credential; test-and-save
  calls the transport once, stores exactly one encrypted profile, shows
  **Connection verified. Provider settings saved on this device.**
- The synthetic credential is absent from the semantics tree and the field clears; Use activates;
  Delete confirms and empties the store. Captures `s108-08`, `-08b`.

### 2.7 Provider failure, invalid output, no usable network, discard
(`providerOfflineAndInvalidOutputKeepEditsAndSaveNothing`,
`providerRefusesToSaveWithoutAUsableNetworkPath`)

- Output `10` refused locally; transport not called.
- Throwing transport: one call, **Provider is offline or unreachable**, zero profiles, typed values
  retained. Capture `s108-09-provider-offline-en`.
- The screen now receives the production network-capability signal. With that signal false, Save
  shows **No network connection…**, makes zero transport calls, stores zero profiles, and keeps
  edits. Capture `s108-15-provider-no-network-en`.
- Back → **Discard changes?** → Keep editing retains; Discard leaves and still stores nothing.

### 2.8 Skills/history, privacy, read error
(`skillsAndHistoryShowBoundedWorkflowsAndStoredConversation`,
`privacyDisclosureNamesLimitsWithoutFakeBackupControls`,
`categoriesAndRulesShowReadErrorWhenStorageCannotBeRead`)

- Skills & history lists five bundled workflows + the stored synthetic conversation; Open AI Chat
  callback fires. Capture `s108-10-skills-history-en`.
- Privacy states local storage, Keystore-encrypted credentials, hosted/masking boundary, retention,
  controls, Android backup disabled and no backup/restore control. Capture `s108-11-privacy-en`.
- Closing the Room database before opening the screen forces the real read-error path: both tabs
  show **These lists could not be read**, `Try again`, no count claims, and the Add action is
  disabled. Capture `s108-16-read-error-en`.

### 2.9 Vietnamese and 1.5× text/IME
(`vietnameseLargeTextKeepsTasksReachableAndLocalized`)

- VI/1.5× categories: wrapping tabs/filters/rows, add sheet with real IME, new `Thú cưng` row.
  Capture `s108-12-categories-vi-large`.
- VI/1.5× provider: localized title/controls, typed name with keyboard, sticky CTA visible after
  hiding IME, protocol sheet localized. Captures `s108-13-provider-vi-ime`,
  `s108-14-provider-vi-large`.

## 3. Actual offline local-configuration run

```
adb shell cmd connectivity airplane-mode enable
adb shell settings get global airplane_mode_on            # 1
adb shell am instrument -w -e class \
  "...#categoriesAddRenameArchiveAndDeleteWithReferenceSafety,...#rulesAddChangeAndRemoveExactDescriptionRules" \
  io.github.triet4p.aegisf.s108evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell cmd connectivity airplane-mode disable
adb shell settings get global airplane_mode_on            # 0
```

Result: **OK (2 tests)** in 29.3 s with airplane mode on on the **pre-round-2** candidate; local
category/rule operations and the earlier delete behavior worked. Raw output:
`logs/instrumentation-offline.txt`. Radio restored to off. The final candidate changed the delete
contract/UI but added no network dependency; its local category/rule behavior is covered by the
14-test native suite without any network use. The airplane condition was not repeated after the
owner restricted further radio changes, and is disclosed rather than re-claimed for the final
candidate.

After the owner’s device-change concern, no second provider-airplane run was attempted. Current
verified state: `airplane_mode_on=0` (`cmd connectivity airplane-mode` = disabled), `wifi_on=0`,
`mobile_data=1`, `bluetooth_on=0`; heads-up notifications restored to 1. The provider-offline path
is proven by the non-radio network-capability signal test above, not by a second radio run.

## 4. Real-shell isolated walkthrough (final candidate)

`pm clear io.github.triet4p.aegisf.s108evidence` (isolated only), launched
`io.github.triet4p.aegisf.s108evidence/io.github.triet4p.aegisf.MainActivity`.

1. Added `Gym`; saved a real **-45,000 ₫** expense under Gym via the manual form/keyboard.
2. Home shows **45,000 ₫** (`device-home-45000-final-dark.png`).
3. Categories → Delete Gym: **This category is still in use**, **1 stored transaction uses this
   category**, **no Delete permanently**, **Archive instead**. Capture
   `device-delete-blocked-final-dark.png`.
4. Archive instead: snackbar confirms past records stay attached; Archived filter shows `Gym` with
   badge `Archived` + caption `History kept`, no doubled phrase.
   `device-archived-row-final-dark.png`.
5. Delete the archived Gym: **This archived category is still used … permanent deletion is
   blocked. Remove those references first:**, only Cancel. Capture
   `device-delete-archived-blocked-final-dark.png`.
6. Added `Gym2` (unreferenced), deleted it through **Nothing stored uses this category** →
   **Delete permanently**; row gone. Capture `device-delete-unreferenced-final-dark.png`.
7. Ledger still shows **-45,000 ₫ · Gym (archived) · Manual**; Home still **45,000 ₫**.
   `device-ledger-final-dark.png`.
8. Bank child launched the system notification-access page and the app-info page; both returned
   with Back. Captures `device-system-notification-access.png`, `device-system-app-info.png`.

Every image above was opened after capture. The visual findings/corrections are in
`visual-review.md`.

## 5. Criterion coverage

| Criterion | Mechanism / evidence |
| --- | --- |
| Focused Categories/Rules tabs and focused sheets | `s108-01/-02/-05`; real category/rule lifecycle |
| Every category mutable incl. default-like | Food rename/archive/deleteRefused; no lock flag |
| Archive preserves history | Archived row + record link + Home/Ledger 45,000 ₫ |
| Active references block unsafe deletion | `CategoryDao.deleteIfUnreferenced` transaction; command Conflict; UI no destructive button; native + real-shell blocked; race test |
| Unreferenced delete remains supported | Transport/Gym2 UI delete + host command test |
| Rules exact-description list/add/edit/remove + mapping | rules test; `s108-05`; plan line 71; README trace |
| Empty/collision/validation/loading/save/test errors | empty/collision, output validation, provider failure/no-network, read-error test |
| Stale/discarded edits | stale archived category rule; delete race; provider discard guard |
| Offline local configuration | airplane-verified OK (2), local DB |
| Settings hub, not stacked forms | `s108-06`; real settings captures |
| Provider/protocol/profile/endpoint/model/key/output | `s108-08/-08b/-09/-13/-14/-15` |
| Skills/history + privacy/retention | `s108-10/-11`; no fake backup |
| Notification permissions/allowlist/intake diagnostics | `s108-07/-07b`; intents resolved/launched; reconnect refusal tested |
| Routine/advanced/destructive distinct | hub/children/Archive instead/confirm dialogs |
| EN/VI, 1.5×, IME, insets | `s108-12/-13/-14`; nav padding |
| No secrets/debug enums/raw panels | masked credential semantics-absent; human protocol names |
| Exact totals preserved | 45,000 ₫ before/after block/archive/delete; ledger record intact |

## 6. Limits and uncompensated points

- **Provider network:** real external provider calls are not made in S10.8; connection success uses
  the existing adapter with a deterministic transport. The no-network path uses the production
  connectivity signal, but no second real-airplane provider run was performed after the owner
  restriction. Real streaming remains S10.5 evidence.
- **Notification access:** the device grant is not enabled; the granted/denied UI uses the existing
  access test hook. The notification-access and app-info system destinations were launched and
  returned; reconnect was **not** invoked because it changes system state and requires owner
  approval. Its no-access refusal path is tested non-radio.
- **Seeded categories:** this build creates no seed rows; the fixture Food/Gym stands in for a
  default-like category and is mutable.
- **Text scale:** 1.5× is app-scoped; system font scale is S10.9 work.
- **Full Android suite:** not rerun; 76 focused host tests + full debug/androidTest compile + 14
  native tests were run.
- The real-shell Vietnamese IME turns automated ASCII into Telex accents, so visible fixtures use
  `Gym`/`gym`; a stray note draft was discarded through the normal guard and no note was saved.

## 7. Round-1 findings resolution

| Finding | Resolution |
| --- | --- |
| F1 archived row duplicated wording | Source split badge `Archived` + caption `History kept`; native assertion against doubled phrase; real-shell `device-archived-row-final-dark.png` opened. |
| F2 stale real-shell capture | Removed the pre-correction `device-archive-instead-result-dark.png`/`device-delete-impact-dark.png`; replaced with current blocked/archived captures opened and indexed. |
| F3 rules spec deviation | Plan line 71 records exact-description mapping; README and native copy trace the existing matcher; no wildcard backend added. |
| F4 line-number drift | Operative plan sentences quoted verbatim in this record, worker summary and handoff. |
| F5 deletion contract | Enforced: atomic reference check + delete; UI never offers destructive delete for referenced categories; conflict re-reads impact; unreferenced delete remains. |
| F6 intents/reconnect | Two system destinations resolve and were launched/returned in real shell; reconnect no-access refusal tested non-radio; state-changing reconnect is a disclosed device-restricted N/A pending owner approval. |
| F7 provider-offline substitution | Production network-capability signal wired; no-network test proves zero transport calls/zero profiles and truthful message; no radio change. |
| F8 read-error/stale | Closed-DB read-error test and stale archived-category rule test added; UI drops stale choice and disables Save. |
| F9 app-scoped 1.5×/full suite | Accepted as before; no action. |


## 8. Round-2 verdict

Independent evidence round 2: **PASS**, `advance:true`, zero actionable findings
(`artifacts/sprint-10/reviews/S10.8-review-02.md`, manifest `S10.8-review-02.manifest.json`,
snapshot `01b756638917d7fb84dafa2bb3e674f9128a3e8c52bbf73ae7e8135b5d9cc580`, 26 scope entries =
25 present wholly-owned product/test paths + 1 deletion; authoritative manifest controls). Main
accepted the completed result and released the worker. No source edit occurred after the final
native run; the separate Bronze-only exact-snapshot checkpoint remains another assignee's task.
