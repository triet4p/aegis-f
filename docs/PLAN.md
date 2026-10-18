# Aegis F — Project Plan

## Overview

Personal Android expense tracker with user-defined categories, on-device AI suggestions, optional bank-notification intake, and bounded expense Q&A. Build and deploy to a physical Android device without Android Studio or an emulator; distribute privately as a signed APK.

**Current state:** Sprints 1–4 complete and accepted. User confirmed final Sprint 4 real-device experience: “hoàn tất, đã ổn”. All Full technical gates and refreshed D5 review passed; latest local checkpoint `05489058`, 214 tests/build, final debug installed. Delivered category probability choice, advisory English preference without language/vocabulary gating, visible model progress and safe permanent category deletion. Prior large-catalog recall 1/6 remains a limit, not a broad adoption claim. Sprint 5 remains pending; no automatic start or push.

## Product Scope

- English-first UI, manual expense descriptions, AI instructions, and assistant replies. Vietnamese-language AI quality is not a release requirement.
- VND only: store amounts as signed 64-bit integer whole dong; show an explicit VND/₫ currency indicator. No floating-point money or currency conversion.
- User-defined categories without a product-imposed count limit; add, rename, archive, permanently delete with financial-record preservation, and select categories manually even when AI is unavailable.
- Record expenses, income, and internal transfers separately; summaries count confirmed expenses, not every bank debit.
- Suggest existing categories with real model probabilities for user selection. English input is preferred, not required: no language/vocabulary eligibility barrier; multilingual quality is not established. Keep explicit financial confirmation and visible model busy/progress states. Suggest new category names without creating them silently.
- Opt-in bank push-notification intake through Android's notification listener; no bank account connection, credentials, SMS access, root, or accessibility scraping.
- All ledger processing and inference remain on-device. Model acquisition may use a deliberate initial download or developer-side file transfer; normal use must not depend on a cloud AI service.
- Bank notifications may contain Vietnamese or other bank-specific text: preserve permitted transaction content, parse supported formats deterministically, and do not assume English AI understands an opaque memo. Missing meaning goes to manual review, not invented categorization.
- Local backup/restore and stable APK signing for continued personal use.

## Delivery Decisions

- Native Kotlin, Jetpack Compose, and Room/SQLite. A single Android target and direct notification-service integration do not need a cross-platform bridge.
- SDK command-line tools, Gradle Wrapper, and adb are the required development path. Pin compatible stable JDK/Gradle/Android Gradle Plugin/SDK versions; Android Studio remains optional.
- Decision-model candidate: English Laya, 421M, through ONNX Runtime Android. The existing English ONNX export or reproducible upstream export must pass tokenizer/decision-head parity and physical-device checks before adoption. English-first removes the need to default to the 322M multilingual checkpoint; it does not establish model accuracy.
- Generative-model candidate: Liquid LFM2.5-350M, quantized GGUF through llama.cpp. Start with Q4_0 and compare Q4_K_M on domain examples; verify runtime revision, license, device footprint, and English task quality. Do not start a new integration with the deprecated LEAP SDK.
- Do not keep both model sessions permanently loaded in the notification listener. Persist intake promptly; run inference off the main thread with bounded work and explicit lifecycle management.
- Notification-derived records are drafts until confirmed. Model confidence, schema-valid output, and a successful bank notification are not proof of an expense.
- Q&A uses validated parameters for fixed read-only app tools. SQLite calculates totals; the SLM must not execute arbitrary SQL or perform ledger arithmetic itself.
- Store unlimited categories in the database, but bound model candidate inputs to the real token budget. Candidate retrieval must include an unknown/no-fit outcome and must not silently lose options through truncation.

## Milestones

- [x] **M1 — Device development path:** reproducible command-line build, user-run install/launch/device inspection, and verified same-key update preserving saved state; temporary probe removed.
- [x] **M2 — Usable local ledger:** manual transaction/category management and exact VND summaries survive restarts; offline and month-edge behavior accepted on the physical device.
- [x] **M3 — Safe bank intake:** supported VietinBank notifications create deduplicated, reviewable drafts without OTP retention; final evidence review and user device acceptance passed.
- [x] **M4 — Existing-category AI:** on-device category probabilities with safe unknown handling, explicit user choice/corrections, advisory English preference and visible progress; final user real-device acceptance passed. Recorded quality/retrieval limits retained.
- [ ] **M5 — New-category suggestions and Q&A:** on-device SLM proposes names and calls bounded database tools.
- [ ] **M6 — Personal daily-use release:** backup/restore, stable signing, privacy/lifecycle checks, and complete real-device scenarios.

## Current Sprint and Roadmap

**Latest completed sprint:** [Sprint 4 — On-device existing-category suggestions](sprint-plans/sprint-4.md), accepted by user: “hoàn tất, đã ổn”. [R4 PASS](../artifacts/sprint-4/reviews/S4.10-R4.md), [checkpoint `05489058`](../artifacts/sprint-4/checkpoints/S4.10-C3.md), [D5 PASS](../artifacts/sprint-4/reviews/S4-D5.md) and final user gate complete. **Next:** Sprint 5, not started; no push authorized.

| Sprint | Goal | Depends on | Status |
| --- | --- | --- | --- |
| [1 — CLI and physical device](sprint-plans/sprint-1.md) | Establish a working Android build/deploy path and device evidence | None | Complete; delivery committed and pushed |
| [2 — VND ledger and categories](sprint-plans/sprint-2.md) | Deliver the manually usable local tracker | Sprint 1 | Complete and accepted; local commit authorized |
| [3 — Bank notification drafts](sprint-plans/sprint-3.md) | Capture permitted bank content safely and review transactions | Sprint 2 | Complete and accepted; closing local commit authorized |
| [4 — Decision-model suggestions](sprint-plans/sprint-4.md) | Show existing-category probabilities for user choice; safe category management | Sprint 2; Sprint 3 before notification integration task | Complete and accepted; Full technical gates, D5 PASS and final user gate passed |
| [5 — Liquid SLM features](sprint-plans/sprint-5.md) | Suggest new names and answer bounded ledger questions | Sprint 4 | Pending |
| [6 — Personal-use release](sprint-plans/sprint-6.md) | Prove recovery, upgrades, privacy, and end-to-end daily use | Sprints 1–5 | Pending |

Execution order is 1 → 2 → 3 → 4 → 5 → 6. The dependency distinction for Sprint 4 permits its standalone model evaluation after Sprint 2 if notification content is temporarily unavailable; notification integration remains required, not removed from scope.

## Workflow and Completion Rules

Use the route and exact gates declared in the active sprint plan. Lightweight work requires specific behavioral verification; Full work additionally requires independent evidence review and a separate Bronze-only exact-snapshot checkpoint at each declared boundary. Tests guard consumer-visible behavior and boundaries; they do not replace launching the app on the physical device.

A sprint closes only when its acceptance criteria, technical gates, and a final user real-experience gate pass. Workers may perform task-level device checks within the current authorized app/related-notification scope, preserving user data and privacy. When UI observation is limited, prefer targeted privacy-safe app diagnostics over blind repeated attempts; request user help only for unreachable steps. Update affected developer/user documentation and remove temporary probes. Commits require explicit scoped authority; never push without separate authorization.

Plans hold scope, status, durable decisions, real blockers, and concise evidence links only. Detailed evidence/handoffs, when needed, belong under `artifacts/sprint-<N>/`; do not commit raw financial data, model binaries, signing secrets, or verbose execution logs.

## Risks and Required Evidence

- Phone baseline is verified: HONOR ALT-LX2, Android 14/API 34, ARM64, 4096-byte pages, reported MemTotal 7837732 kB. S4.9 actual-device evaluation measured cold p50/p95 10535/11159 ms (n=3), warm 903/914 ms (n=20), and sampled peak PSS 1620186 KiB; small-sample/sampling limits remain. S4.10/S4.11 final debug phone checks prove UI behavior, not a rerun of quality or performance evaluation.
- Actual notification content and restrictions depend on the bank, Android version, and settings. Sprint 3 validates the user's bank rather than assuming a generic parser is sufficient.
- English Laya has user-run Android runtime parity evidence. Quantization, domain accuracy, scalable candidate behavior, and daily-use suitability remain subject to Sprint 4 adoption gates.
- Liquid 350M's English tool-use and new-category quality must be measured on this domain. A larger Liquid checkpoint is an evaluated alternative only if the small model fails and the device can support it; do not silently replace the requested offline architecture with cloud inference.
- Notification access is not a historical transaction feed. Summaries are over recorded confirmed expenses; UI must not imply complete bank reconciliation.

## Completed Sprints

- Sprint 1 — CLI build/deploy path and user-run launch/update proof complete; published to `origin/main` (plan closure `154d34c6f11836a9951f8afffd885c6b004e4e57`).
- Sprint 2 — VND ledger, categories, offline persistence, and month-edge behavior complete and accepted.
- Sprint 3 — Bank notification drafts complete and accepted; [final evidence review PASS](../artifacts/sprint-3/reviews/S3.8-R3.md). Closing local commit authorized; commit execution not established by this plan.
- Sprint 4 — On-device category probability selection, correction rules, bank review, visible inference progress and safe permanent category deletion complete; [final differential review D5 PASS](../artifacts/sprint-4/reviews/S4-D5.md), latest reviewed checkpoint `05489058`, final user real-device acceptance “hoàn tất, đã ổn”. Quality/retrieval limits retained; no push claimed.

## Out of Scope / Future Work

Vietnamese AI/UI localization, bank APIs and account linking, SMS ingestion, cloud sync, multi-currency, receipt OCR, voice input, shared accounts, Play Store publishing, subscriptions, and automatic model fine-tuning are not part of this delivery.

## Technical References

- [Manage SDK packages](https://developer.android.com/tools/sdkmanager) and [Android CLI](https://developer.android.com/tools/agents/android-cli): command-line setup; check current Windows limitations and tool lifecycle before installing.
- [Build/install from the command line](https://developer.android.com/build/building-cmdline) and [Gradle/JDK compatibility](https://docs.gradle.org/current/userguide/compatibility.html).
- [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [restricted settings](https://support.google.com/android/answer/12623953), and [Android 15 OTP redaction](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction).
- [Laya checkpoint family](https://huggingface.co/convaiinnovations/laya), [Laya source/export support](https://github.com/NandhaKishorM/laya), and [English ONNX export](https://huggingface.co/receptron/laya-onnx).
- [Liquid LFM2.5-350M](https://huggingface.co/LiquidAI/LFM2.5-350M), [official GGUF files](https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF), [Android llama.cpp integration](https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile), and [LEAP SDK deprecation](https://docs.liquid.ai/deployment/on-device/llama-cpp/migrating-from-leap-sdk).
