# LT1 main-app UI and database proof

## Boundary and measurement

- App: installed main package `io.github.triet4p.aegisf` on the owner device, same-signer `adb install -r`; no clear-data/uninstall, key or credential export, confirmation, category selection, ledger write, or test-app install.
- The read-only database inspection copied the live main DB and WAL/SHM into a temporary directory, queried only the rows listed below, then deleted the temporary copy. No production DB or raw private trace was added to the repository.
- Effective input sources for draft 1: its primary classification description plus the active linked reading from event 2, through accepted link revision 1. This is source-bound current context, not arbitrary free text. The translation is derived input; original memo/capture/reading/link remain owner evidence and were not edited.
- Category IDs: 1 Foods, 2 Transport. All values below are the recorded local Laya outputs. No candidate, category, threshold, weight, or confidence tuning was made.

## Baseline before translation

- Snapshot: draft 1, `NO_FIT`, candidate IDs `1,2`; Foods `0.102`, Transport `0.2226`, unknown `0.6753`, confidence `0.2423`.
- Model revision: `68f27dfe5a27a54fb2b1fefc432f43f972e90868/512/192`; eventIds `2`.
- Stored-source input identity/effective digest: `f09936bc7020ceb6be70e0e66a1e1289df28f6274ea9d73204419698e3068b91`.
- Draft was PENDING at review revision 0; event 2 link was ACCEPTED at revision 1; transactions count was 0.

## Actual UI requests and outcomes

### Run row 25 — first-pass translated classification

- Allowed skill: `PREPARE_LAYA_REVIEW` only. Exact 204-character request: “Translate pending draft 1 source context into faithful English, preserving exact masked tokens, proper names, uncertainty and missing facts. Then classify locally without changing or confirming the draft.”
- Trace events 17877/17878 are the `classify_draft` call/result. The hosted call carried both `draft_ref` and `classification_context_ref` plus a 174-character `english_translation`.
- Result provenance: `machine_translated_english`; input identity `745fb9e6669d02390e1ef6483d8fae1d016d300c1835f9da15a80abe31bb395e`; effective-input digest `0f2b15fdd4d956b47bf59937e71fa86a94e063aecf62571ca32d741bcfe139a3`; translation source revision `5a1e7093018bddfb73945361901979f9a7a23a5b957eee16f5e6e697fe2d55eb`; eventIds `2`.
- Snapshot: `NO_FIT`; Foods `0.1481`, Transport `0.2644`, unknown `0.5874`, confidence `0.1379`. Transport ranked above Foods; no category was selected.
- Quality note: this submitted translation mixed English with untranslated Vietnamese descriptive wording. This is not a faithful-English quality pass. The complete private tool trace remains only in the production DB.

### Run row 26 — misrouted correction request (counted)

- This was an actual UI request using the same 350-character correction prompt quoted under run 27, not excluded: the destination's default intent had reset to `INTERPRET_EVENTS` after navigation. Run state was `SUCCEEDED` with a 1,619-character chat response and 422 trace events, but it had no `classify_draft` call/result and did not produce or reuse a translated classification snapshot.
- Record it as a misrouted/failed correction attempt, not as translation or local-classification evidence. It caused no source, review, link, or ledger change.

### Run row 27 — final correctly scoped correction

- Allowed skill was explicitly set to `PREPARE_LAYA_REVIEW` only; `INTERPRET_EVENTS` was unchecked. No further hosted request was made.
- Prompt: “For pending draft 1, translate ALL descriptive Vietnamese phrases in the current memo and every active linked reading into natural English. Keep proper names and merchant and bank names as written. Copy every masked token exactly. Preserve stated uncertainty and missing facts. Then classify locally. Do not add facts or change or confirm the draft.” It contains no desired category or category-name hint.
- Trace events 18944/18945 are the actual `classify_draft` call/result. The call carried both source references and a 179-character model-produced `english_translation`. The result marked `machine_translated_english`, identity `ecdf13c8f016a2ff3d998321ef647cb303c5efa8ab4083a08a164ff7ae016c29`, effective-input digest `9cfda2e87d567ccaf83dbd4bfa67f0ed9894a5cd3df8869089e57bfad6c398c1`, source revision `5a1e7093018bddfb73945361901979f9a7a23a5b957eee16f5e6e697fe2d55eb`, and eventIds `2`.
- Snapshot 1 stores that exact identity and digest, `HOSTED_ENGLISH_TRANSLATION`, the same source revision, `staleReason=null`, and the same model revision as baseline. The local `classification_input` identity/digest in the tool result exactly match the persisted snapshot, proving this translation reached local Laya and has a distinct input identity from both the stored-source baseline and run 25. The raw Vietnamese source was not appended to the translated primary input.
- Sanitized exact purchase clause from the model output (merchant and neutral date/time tokens replaced; food item preserved): `Bought bánh mì at [merchant], on [masked source token] approximately [masked source token]-[masked source token]`.
- The transfer and purchase action wording was rendered in English; the food item remains the dish term “bánh mì,” which may be a legitimate English loanword. This evidence does not assert a translation-quality pass/fail; reviewer adjudication of the exact sanitized clause is appropriate.
- Actual local result: `NO_FIT`, Foods `0.0889`, Transport `0.1566`, unknown `0.7545`, confidence `0.3464`. Transport remains the top category, unknown is dominant, and Foods did not win. Do not call the owner’s desired category successful.

## Data and privacy result

After the final run, draft 1 remained PENDING at review revision 0 with no confirmed transaction; event 2 remained ACCEPTED at link revision 1; transactions count remained 0. Snapshot eventIds remained `2`. No category or financial decision was made. The exact full provider/tool responses remain in the local production trace; only sanitized excerpts, IDs, digests, and measured output fields are recorded here.

Screenshot: `main-ui-redacted.png` is an actual main-app screenshot after run 27. The private assistant response/provider header are opaque-redacted; the non-sensitive request field and app navigation remain visible. The temporary English Australia keyboard layout was removed after the last UI request; the Gboard screen now lists Vietnamese only and secure selected subtype was restored to the original `-1680759253`.
