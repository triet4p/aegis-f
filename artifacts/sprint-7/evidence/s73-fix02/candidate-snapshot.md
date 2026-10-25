# S7.3-fix-02 candidate snapshot

**Task / attempt / worker:** S7.3 / S7.3-fix-02 / `S73RetargetSilverFix` (`silver-task`)  
**Review:** `S7.3-cycle-1`, round 3; explicitly authorized by Main after round-2 found the accidental build-config deletion (race resolved by prior candidate and independent checks; one bounded correction and verification authorized).  
**Criterion:** `docs/sprint-plans/sprint-7.md` S7.3, unchanged; correction for final differential finding 1 in `artifacts/sprint-7/reviews/S7-final-review-01.md`.  
**Base:** `e291db64dcb42056278aff0586e3c774a43c234a` (`refs/heads/main`).  
**Candidate product snapshot:** SHA-256 `e06922c6b61e2213d72626ce73d18b24199bd2d756bec9af4ac6780e6abce23d`, computed as SHA-256 over the deterministic `sha256sum` output for the five listed candidate files. `app/build.gradle.kts` matches the base exactly; `targetSdk = 37`, with no smoke suffix/runner/dependency changes.

Final restored-tree verification: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain --rerun-tasks` succeeded with 418 tests, 0 failures, 0 skipped. Normal APK is 88,892,997 bytes, SHA-256 `f2221ea6b6a30d378a71f875082ca59aeead0006b2e79355cf1724e3aae8ed3c`; manifest `targetSdkVersion:'37'`. Exact-base comparison of `app/build.gradle.kts` was empty.

| Candidate file | SHA-256 |
| --- | --- |
| `app/src/main/java/io/github/triet4p/aegisf/data/ClassificationWorkDao.kt` | `e98142014b08849510ba38f4e667b72eecf4833e033f07646faf40c355bc18f2` |
| `app/src/main/java/io/github/triet4p/aegisf/data/BankDraftClassificationQueue.kt` | `555f4e7cd9b73b189f59ea1b1751a95608fe879a9230263123f8df5d9ade7024` |
| `app/src/test/java/io/github/triet4p/aegisf/data/ClassificationWorkLifecycleTest.kt` | `5ed6911835e58fd5fbcc9e8edc4e1bf49392d379c6110d81ba6fb0db33d6ee49` |
| `CHANGELOG.md` | `08bf82a4ca27bab91ba4df66afeea7fdbb976abbb383dbfec9ee2bc3591bee58` |
| `.agents/memory/lessons-learned.md` | `37d0e40c831e89c3cff73a8e6f322b8a99524c821e3bec21a1b36221c7be5a45` |

The temporary instrumentation source and package suffix/dependencies were removed before the final build. Main-owned plan files and unrelated untracked artifacts were not included or edited. No schema change was made.
