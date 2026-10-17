# English Laya artifact — provenance and contract (S4.1)

Primary upstream sources are linked from `docs/PLAN.md` Technical References.
All pins below were read from those sources on 2026-10-16.

## Selected artifact

| Item | Pin |
| --- | --- |
| Checkpoint repo | `convaiinnovations/laya` (family hub; English checkpoint at repo root, ModernBERT-large backbone, 421M total) |
| Checkpoint revision | `7b928d828b7b0e022f929d9bd2e44165aa270148` (Hub `sha`, `lastModified 2026-10-03T18:00:07Z`) |
| Checkpoint license | Apache-2.0 (`license:apache-2.0` tag and card front-matter) |
| Export repo | `receptron/laya-onnx` (`base_model:convaiinnovations/laya`, `license:apache-2.0`) |
| Export revision | `68f27dfe5a27a54fb2b1fefc432f43f972e90868` (Hub `sha`, `lastModified 2026-09-19T16:42:28Z`) |
| Export license | Apache-2.0 (card front-matter; weights are Convai Innovations', export code MIT per `receptron/laya`) |
| Reference sources | `NandhaKishorM/laya` @ `4ac226d5ee88ed6df8db288a05c766d7a34ee682` (`rl_common.py`, `rl_agent_api.py`); `receptron/laya` @ `6478649e723122ca24bbf5fb69ed1010023c9750` (`export/export_onnx.py`, `src/sequence.ts`, `src/laya.ts`) |
| Export claim | max logit difference vs. PyTorch reference ≈ 1e-5 (export card + `export_onnx.py` parity print) |

The multilingual checkpoint (322M) is not used: English-first product scope does
not require it, and Sprint 4 selects the English 421M candidate. Jev's hosted API
is not an offline fallback.

## Bundle layout (external weights, 5 files)

`BUNDLE_FILES` per `receptron/laya`: `laya.onnx`, `laya.onnx.data`, `laya_config.json`,
`tokenizer/tokenizer.json`, `tokenizer/tokenizer_config.json`. Observed sizes and
SHA-256 of the pinned bundle (machine-local ambient cache, never committed):

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `laya.onnx` | 3807291 | `a874eb254b58b0fcb1e7ad56fbb188c29d64e08c9a46b689433e1f52c66dba1e` |
| `laya.onnx.data` | 1685258240 | `487746363a8da57bcadb4345352997d22a0fb90d70aa22c6856668d023242aba` |
| `laya_config.json` | 369 | `5049005dc6ae3ca5e82cc7d85c421357d5c543817300c8e8c5281ddbc69bb561` |
| `tokenizer/tokenizer.json` | 3583228 | `6c8aaa9a542084f2457eab775d4eeb51f92a70c0fd9de28d5edb0ddec3c08d30` |
| `tokenizer/tokenizer_config.json` | 308 | `50044de60daaa73df97d262e15a40d4faf0160e7d742df64b377877a1320dd12` |

The export tokenizer is byte-identical (SHA-256) to the checkpoint's
`tokenizer/tokenizer.json` at the pinned checkpoint revision.
- Opset `18`, single domain; 1842 nodes across 39 distinct operators: Abs 1, Add 185,
  And 6, Cast 4, Clip 3, Concat 130, Cos 2, Div 32, Erf 30, Expand 5, Gather 11,
  GatherElements 1, GatherND 1, Gemm 4, GreaterOrEqual 1, IsNaN 28,
  LayerNormalization 62, LessOrEqual 1, Log 2, MatMul 182, Max 1, Mul 262,
  Neg 57, Not 2, Range 2, ReduceSum 2, Relu 2, Reshape 138, Shape 34, Sin 2,
  Slice 286, Softmax 32, Split 28, Squeeze 89, Sub 2, TopK 1, Transpose 158,
  Unsqueeze 21, Where 32 — all standard CPU-supported ops; no training-only or
  exotic operators. Session opens on onnxruntime CPUExecutionProvider (host ort 1.21.1).
- Inputs: `input_ids` [batch,seq] int64, `attention_mask` [batch,seq] int64,
  `marker_pos` [batch,options] int64, `marker_mask` [batch,options] bool,
  `qtype` [batch] int64. Outputs: `logits` [batch,options] float32 raw
  (masked slots −1e4), `act_probs` [batch,2] float32 (softmax applied in-graph).
- External-weight layout confirmed: 242 initializers, 203 with external data in
  `laya.onnx.data`. Dynamic axes: batch, seq (min 8), options (min 2).

## Tokenizer and option markers

- Tokenizer: BPE, 50280-entry base vocab plus 116 added tokens (ids 0 and
  50254–50367). Special tokens: `[UNK]` 50280, `[CLS]` 50281, `[SEP]` 50282,
  `[PAD]` 50283, `[MASK]` 50284, plus `<|padding|>` 1 and `<|endoftext|>` 50279.
  Encoder config agrees: `vocab_size` 50368, `pad_token_id` 50283,
  `cls_token_id` 50281, `sep_token_id` 50282. The five `[MASK]`-adjacent surface
  ids are added-token space, not encoder ids.
- Sequence layout (ported from `rl_common.build_sequence`, unchanged):
  `[CLS] <type> question: instructions [SEP] [MASK] opt0 [MASK] opt1 … [SEP] state [SEP]`.
  Each option is scored at its own `[MASK]` marker; logits are softmaxed over
  that question's options only. Choice options render `name: description`
  (`render_options`); noul is always `[false, true]`; score levels are
  `level i: <rubric>`. String states pass through verbatim
  (`json.dumps(ensure_ascii=False)` equivalent); dict states serialize the same way.
- The literal `[MASK]` text is scrubbed from instructions/options/state before
  encoding, so prompt-like descriptions cannot inject a marker. The reference
  pads right with `[PAD]` to the batch longest/widest (`collate_items`).

## Head budget

From the pinned `rl_agent_config.json` (checkpoint) — byte-equal values in the
export `laya_config.json`:

- `max_len` 512 (state truncated after the question header), `head_max_len` 192
  (per-question option budget), `head_layers` 2, `max_prefixes` 6 (episode-only).
- Option-text rule: each option keeps at most 48 tokens; if the head budget
  remainder drops below 16, every option shrinks evenly to
  `max(4, (head_max_len − 16) / n_options)` tokens, and the head keeps at least 8
  tokens. Questions that still do not fit are refused (`options do not fit`),
  never silently truncated — the runner returns `over_budget` and S4.4 owns
  candidate retrieval.
- Decision head: 2 transformer layers over type-embedded encoder states, an
  option-marker scorer (LayerNorm→Linear→GELU→Linear→1), and an act/escalate
  head over pooled state plus answer-distribution features. No text is
  generated; there is nothing to parse.

## Calibration (temperatures are applied, raw logits are never probabilities)

- Per-qtype: choice 1.6369030475616455, score 1.2514300346374512,
  noul 1.983399510383606.
- Per-cardinality buckets (`temp_bucket(qtype, k)`): `choice:2` 1.9063563346862793,
  `choice:3-5` 1.7601518630981445, `choice:6-10` 1.0000158548355103,
  `choice:11+` 0.10058280825614929, `score:3-5` 1.2514300346374512, `noul:2` 1.983399510383606.
- All nine S4.1 examples use 6 options → bucket `choice:6-10` → T = 1.0000158548355103.
- Confidence is Jev-style `1 − H(p)/ln(k)` (`confidence_from_probs`), rounded to
  4 decimals like the reference API; it is a distribution sharpness score, not
  an accuracy guarantee.

## Reference runner and parity

- `tools/laya/reference_runner.py` ports the exact contract above in dependency-light
  Python (`onnxruntime` + `tokenizers` + `numpy` only at run time).
- `tools/laya/expense_examples.json`: 5 active user-style categories plus an
  explicit `unknown` no-fit option; 9 labeled cases (4 meaningful-choice,
  2 no-fit/unknown, 3 boundary: mask-injection, long-state, empty description).
- Parity: the runner's probabilities/confidence match the export author's
  `@receptron/laya` `Laya.load({revision: 68f27dfe…}).systemOne` bit-for-bit
  (4-decimal rounding) on `Monthly rent`, `Lunch at a cafe, 45000 VND`, and
  `Payment to Alex`. Raw-logit spot check on `Monthly rent`:
  `[-2.5694, -2.0874, 4.1281, -2.4, -2.9104, -0.0525]` → Housing after T≈1 scaling.

## On-device evaluation and limits (S4.9)

- The Android `LayaInferenceLifecycle` ran the pinned export on an ALT-LX2
  (Android 14/API 34, ARM64, 4096-byte pages). The synthetic fixture labels were
  left independent of model and parity outputs; only the production-shaped
  names-only `CategoryEntity` metadata was used. Per-case evidence is in
  [`S4.9-A1-evaluation.json`](../../artifacts/sprint-4/workers/S4.9-A1-evaluation.json).
- Top-choice quality was **7/8** labeled non-empty cases, both labeled no-fit
  cases returned unknown, and the long-state case was wrong (expected `Food`,
  selected `Fun`, confidence `0.6976`). No wrong choice met the report's
  `0.8` confident-error threshold; this is not a production threshold or an
  accuracy guarantee.
- With 206 synthetic active categories, names-only shortlist recall was **1/6**.
  Five positive inputs had no category-name evidence and remained manual. The
  current category schema has no aliases or descriptions for retrieval.
- Production-request latency: cold `n=3`, **10,535 ms p50 / 11,159 ms p95**,
  including session close/reopen, open-time probe, and decision; warm `n=20`,
  **903 ms p50 / 914 ms p95**. Sampled process PSS peak was **1,620,186 KiB**
  using `Debug.getMemoryInfo().totalPss`, 79 samples with observed sampling
  intervals of 113 ms p50 / 220 ms p95.
- The app requested no `INTERNET` permission and executed locally; network
  settings were not changed and airplane mode/network denial was not claimed.
  The accepted S4.2 parity report remains archived separately at
  `artifacts/sprint-4/reviews/S42-parity-report.json`.
- Adoption remains limited to optional, explicit-confirmation suggestions with
  manual fallback. The small labeled set and **1/6** large-catalog retrieval
  recall do not justify automatic assignment or confidence-based trust.
