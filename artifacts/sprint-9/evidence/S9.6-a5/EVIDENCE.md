# S9.6-a5 evidence index

Base `c74d0c6d9933749739515789ba748f1af9c44d1a`. All owned changes uncommitted in
worktree `F:/ai-ml/aegis-f`. Foreign Main-owned plans `docs/PLAN.md`,
`docs/sprint-plans/sprint-9.md` preserved untouched by this worker.

## Final verification (current cleaned candidate)

- Full JVM: `:app:testDebugUnitTest` — **567 tests, 0 failures, 0 errors, 3 skipped**
  (bg_81, `TEMP=F:/s95tmp TMP=F:/s95tmp --no-daemon`).
  Intermediate note: bg_80 on the same candidate showed 1 flake
  (`LedgerDatabasePersistenceTest.transactions are observed newest first without
  re-querying`, `ConcurrentModificationException` in the test's own emission-list
  iteration); rerun alone 11/11 PASS and final full rerun 567/0/0/3. No production
  code changed for the flake.
- Lint: `:app:lintDebug` — **BUILD SUCCESSFUL** (bg_81 + bg_82).
- Isolated APKs (`-PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence`):
  `app-debug.apk` sha256
  `1ce6e38194f7cf90e4aaa319760403ff376639019f85e7e19a5e89519c7edec0`,
  `app-debug-androidTest.apk` sha256
  `a99f1b38eff301d107b9ab78273d1a4bb76110604f1ff3d0d75c6b084088a24e`.
- Phone (AAAJBB4930914144, isolated `s812evidence` pkg, `adb install -r` both APKs):
  `S96ActionableChatPhoneInstrumentedTest` — **OK (3 tests)**:
  unlink-live-cards, attach-tap (`PROPOSED` card -> `Attach draft` ->
  `ACCEPTED`, draft `PENDING`, ledger empty), reject-on-ambiguous-pair
  (`PROPOSED` -> `Reject` -> `REJECTED` retained, draft `PENDING`, ledger empty).
- Targeted JVM: `BoundedWorkflowExecutorTest` hidden-row follow-up + single-use/blank
  2/2 PASS; `ChatOutcomeReferenceTest` 7/7 PASS.

## What changed in a5 (owned)

- `BoundedWorkflowExecutorTest.kt`: +1 consumer test — a follow-up naming a hidden
  draft id completes without creating any link row, keeps the draft `PENDING`,
  writes no ledger row, and the hidden draft is absent from `visibleRefsOf`.
- `S96ActionableChatPhoneInstrumentedTest.kt`: +1 attach-tap test (ambiguous decoy so
  the pair stays `PROPOSED` for the tap); restored the inherited reject test
  (was dropped by an intermediate a5 edit, restored); DELETED the record-callback
  probe and the failing production-resume harness test per Main direction; removed
  now-unused imports.
- `QuickCaptureScreen.kt`: removed the `chat-capture-{id}` test-tag seam (no test
  references it anymore).
- `ChatOutcomeCards.kt`: retry copy now says the retry re-sends through the same
  provider and scope (no behavior change; retry still only renders for `Incomplete`).
- `CHANGELOG.md`: one `Changed` bullet for visible-scope follow-up continuity.

## Deliberately not done

- No resume-harness replacement: the offscreen-vs-loaded gap stays
  undiscriminated, so no new harness asserts it. Production resume proof remains
  the a4 surface record (`evidence/S9.6-a4/`, unique `S96WxQvK7391` seed, PNG+XML
  pre/post force-stop, no reseed) plus a3 `FACTUAL-AUDIT.md` preserving the red
  harness failure evidence.
- No cancel-tap-while-running device proof: the busy window is shorter than the
  human-tap loop. Consumer evidence is the existing durable-history contract
  (`cancel leaves the stored effect intact and never claims rollback`,
  `late reply cannot settle a cancelled attempt`, both PASS in the 567).
- No hosted category-save rerun in a5 (S9.5-accepted path; old 401/503 not retried).
- Chat cards deliberately do NOT deep-link into the Manual editor: the record
  action is the `onRecordEvent` callback owned by `AppShell`, so no new
  navigation proof was invented here.
