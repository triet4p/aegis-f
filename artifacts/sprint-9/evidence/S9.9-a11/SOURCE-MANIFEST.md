# S9.9 reviewed-snapshot manifest (full uncommitted scope since the accepted base)

Attempt `S9.9-a11` / runtime `S99FiveTrialCompletion`, prepared for review cycle `S9.9-cycle-1`
round 1. Repository `F:/ai-ml/aegis-f`, Git dir `F:/ai-ml/aegis-f/.git`, ref `refs/heads/main`, base
commit `1cd84dd7a14aa4073b8abb74f11f6de180de8ab3` (unchanged; no commit in this attempt).

The reviewed candidate is the current working tree. All uncommitted S9.9 scope since the accepted
base is listed below (source, tests, docs); `artifacts/sprint-9/` is the evidence tree and has its
own `MANIFEST.sha256`. The root `MANIFEST.sha256` is a pre-existing one-line stray untracked file
(not created or used by this attempt).

## Source, tests and docs in the candidate

| path | sha256 | status |
|---|---|---|
| `.agents/memory/lessons-learned.md` | `8a5284d842ed0aae625a99cae0ebf0269506514a691f7e1f4fb3cdaada62399c` | M |
| `CHANGELOG.md` | `9dfee4f2bf477f48c65e897aa522ed1d43dbc1f21417d205a0ce2b8588aabe1d` | M |
| `README.md` | `91ddaa147ae2b7a4836f42f08cbc9769d10630657d3b00f9c67996ef958c9562` | M |
| `app/src/androidTest/java/io/github/triet4p/aegisf/data/S93ReversibleLinkPhoneInstrumentedTest.kt` | `3a6798cadaf1b2873a17a5caaf990496a3977c60b1433c470d4348fbdf0bd388` | M |
| `app/src/androidTest/java/io/github/triet4p/aegisf/data/S98DeviceFixtureInstrumentedTest.kt` | `3aa82b7446e708d8db50c77c7be3e4fc96dca6f460adbec34dadacdfc12cc748` | M |
| `app/src/androidTest/java/io/github/triet4p/aegisf/data/S99WorkflowPhoneInstrumentedTest.kt` | `413f7d98fea7a5465bf29239a32595e850dc7d9c88d47335f3ece77430aeb53c` | ?? |
| `app/src/androidTest/java/io/github/triet4p/aegisf/provider/HostedProviderSettingsStoreInstrumentedTest.kt` | `a30c6613a9f606238eb79dde61f8427ffb7475303717766c08952f00f555d0a8` | M |
| `app/src/androidTest/java/io/github/triet4p/aegisf/provider/S812HostedSettingsChatInstrumentedTest.kt` | `2a24a396b4775b006b59acf39e0bc5eda0887e83b4a68b9508f712ee2efaf450` | M |
| `app/src/androidTest/java/io/github/triet4p/aegisf/ui/S99ChatUiInstrumentedTest.kt` | `6f7523d57de2c8cc84615274c19321731d0f3b153b088e91c5136d48deea07de` | ?? |
| `app/src/androidTest/java/io/github/triet4p/aegisf/ui/S99MeasurementInstrumentedTest.kt` | `cff889433a43993d9ce9e915398a7dc0d430f9e8bb8e5a37103f83fdb017ccf2` | ?? |
| `app/src/main/java/io/github/triet4p/aegisf/agent/AgentInterfaceContract.kt` | `7aa90f28c0c8f70f16eaeecc58e987fd24e1f011b5d019a88593a7195005a159` | M |
| `app/src/main/java/io/github/triet4p/aegisf/agent/AgentRunFailure.kt` | `ebfb8cfc0c7750c3ff034647b37ef545d43e1fd9210230747a29a91bb05d818e` | M |
| `app/src/main/java/io/github/triet4p/aegisf/agent/AgentToolRuntime.kt` | `de80a85e4e86c710a426788188ce3e4cd4d80cd43f4eb7bf1f2c0eda6d2ec573` | M |
| `app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutor.kt` | `3e7010e0b8ba440233e3861583c7b11c2fbd7fee462d34a4c262f948f89952fd` | M |
| `app/src/main/java/io/github/triet4p/aegisf/data/PendingBankDraftDao.kt` | `c5f3d8fc5a6cfac7bba8dfceee5d67ac15a2b4655939d266be78168795c181e2` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/GeminiGenerateContentAdapter.kt` | `d64d93fe20df41c6bf22865ba70e9989efd2ab4c7b58a0c5671a3b740153e8bf` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/HostedCacheIdentity.kt` | `c1ef1275f06582d6a8abda367e03e4e0d36ced11dd410fe91e30d5a28ee953ab` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/HostedGeneration.kt` | `69e0bca6b368df597cd18e326420800d8ae16b57ad3bcaaaa02a57aa21c0e1e8` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/HostedProviderPresets.kt` | `26a8cad2e59ed655b11337bc859e75050a7ec5c740b267a22f28cd31b0579b68` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/HostedProviderSettingsStore.kt` | `05f2a08422844276a3dcb17146ebe77029676193c7e2ab3f6e1c985de3456fe6` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/OpenAiChatCompletionsAdapter.kt` | `686b1ad019abc354b9577516b1c34edd162cb0c0824d372f1192983fd2e109fa` | M |
| `app/src/main/java/io/github/triet4p/aegisf/provider/OpenAiResponsesAdapter.kt` | `e8a3a9056648b01fdbc94520fc2a482218cbbeebc4731dcf16475f5820288f6b` | M |
| `app/src/main/java/io/github/triet4p/aegisf/ui/HostedProviderSettingsScreen.kt` | `37fff7845fcb9e9893535d290eff9cf6f6445df0a845b4c999018124cbf83a41` | M |
| `app/src/test/java/io/github/triet4p/aegisf/agent/AgentRunFailureTest.kt` | `1bad933852f8828d7d59b979103939e88781bb362949d81983eeb61b9a1f569e` | ?? |
| `app/src/test/java/io/github/triet4p/aegisf/agent/AgentToolRuntimeTest.kt` | `b2ab4f682e64532cbc9f12e7e1ec6204518baaf0e55bba2cc1c30b1f14d0ba4a` | M |
| `app/src/test/java/io/github/triet4p/aegisf/agent/SavedOrderLabelTest.kt` | `0925e8031ac59159211b37787b0c4002e9fd5dfb88c16a0686e52dfa0d49822a` | ?? |
| `app/src/test/java/io/github/triet4p/aegisf/provider/GeminiGenerateContentAdapterTest.kt` | `0af98f90c71c4f7ee02ea7ab2daf07ef1ce039b66d9b0346c8f1e9fcb1b73ab2` | M |
| `app/src/test/java/io/github/triet4p/aegisf/provider/HostedProviderConfigTest.kt` | `1b7e90c335a365cc0b23f126e11e7939179f044a0f0ac766dfb9991a6a793d62` | M |
| `app/src/test/java/io/github/triet4p/aegisf/provider/HostedProviderPresetsTest.kt` | `c64ccb4423093c857129c1a55d3398db25735a22b29adc8c7a20b71608a6104b` | M |
| `app/src/test/java/io/github/triet4p/aegisf/provider/OpenAiChatCompletionsAdapterTest.kt` | `7ea08d7cc5689cb6c96d1385e8d0dcada2aeeb8cab591aec32520e8c0e31ca11` | M |
| `app/src/test/java/io/github/triet4p/aegisf/provider/OpenAiResponsesAdapterTest.kt` | `dd63821c1554aca696eec59cba8394744b90076f23c344aebfb50bb6a2d834b6` | M |
| `docs/PLAN.md` | `d5dc8ddcafc4637c7e984465e3ce0caf62ed7c69e29bc3402f4e266254fa7578` | M |
| `docs/sprint-8-agent-contract.md` | `24c0c24d6ee7a3fe3f67bcff999d56efb87c348176bf66769823720d0ef6c603` | M |
| `docs/sprint-8-agent-interface-redesign.md` | `fff2862025cbda9e8cad5e83592759cbeb37367a7d3b2c991f9690cdb615c91d` | M |
| `docs/sprint-plans/sprint-9.md` | `4a61ccaac58c725df1c9829ab4d18da9f86948f952ffb82ca4016509cb4b15c2` | M |

`docs/PLAN.md`, `docs/sprint-plans/sprint-9.md` and Main supervision records are Main-owned and were
not edited by this worker. Unrelated earlier-sprint modifications are untouched.

## Required docs / callsites referenced by the candidate

- Clarification-anchor provenance: `AgentToolRuntime.makeTimeAnchor` (bound `eventId` plus the
  occurrence source key), exercised live in `run-62e0fe0e…`; documented in
  `docs/sprint-8-agent-interface-redesign.md` (§6.4) and `CHANGELOG.md` (Unreleased → Fixed).
- Hosted category write and origin: `WorkflowCommandService.selectDraftCategory` sets
  `ReviewCategoryOrigin.HOSTED` for `WorkflowCallerScope.AgentSession`; the read-only audit now
  exposes `category`/`origin` per draft; `PendingBankDraftDao`/`PendingBankDraftEntity` carry the
  column; `docs/sprint-8-agent-contract.md` documents the tool contract.
- Output-token acceptance (provider default / explicit ≥ 32768 within capability):
  `CHANGELOG.md` Unreleased → Changed ("limit remains available and must be at least 32768 tokens; it
  is sent as the protocol's own …"), `HostedCacheIdentity` (`PROVIDER_DEFAULT_OUTPUT_TOKENS` when
  null), `OpenAiChatCompletionsAdapter`/`OpenAiResponsesAdapter`/`GeminiGenerateContentAdapter`
  (cap omitted when absent), `HostedProviderSettingsScreen`/`HostedProviderPresets` (explicit field).
  Live evidence this attempt: the staged profile had `maxOutputTokens=null` and the pinned guard
  reported `limitFields=[]`; the run audit recorded `"maxOutputTokens": null` in the admitted run.

## Built artifacts installed on the phone (device hash-equal)

| artifact | sha256 |
|---|---|
| isolated app APK (`io.github.triet4p.aegisf.s812evidence` base.apk) | `2e2e5fcf0cb6ffe4858b42499e0efbdde1ab54233aec4eb57aa026d14ccb58e8` |
| isolated test APK (`…s812evidence.test` base.apk) | `62b07ed3eca376fd4528157208409a32552a39831c6aee00acc2c203c35d8110` |

The two live runs used the same app APK (`2e2e5fcf…`). The test APK first installed for the runs was
`2d5d5dbc…`; the final test APK differs only by the read-only `category`/`origin` audit fields.
Evidence integrity for this attempt is in `artifacts/sprint-9/evidence/S9.9-a11/MANIFEST.sha256`.
