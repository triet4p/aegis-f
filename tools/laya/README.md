# Laya reference tooling (S4.1)

Host-side reference for the English Laya decision artifact. No Android code,
no app dependency, no committed weights.

- [`PROVENANCE.md`](PROVENANCE.md) — pinned revisions/hashes, license, graph
  operators, I/O contract, tokenizer/marker/head-budget/calibration values,
  parity evidence, and limits.
- [`reference_runner.py`](reference_runner.py) — dependency-light reference
  runner: builds the exact checkpoint sequence/marker inputs, runs the real
  pinned ONNX bundle through onnxruntime, applies the per-cardinality
  temperature, and reports calibrated choice probabilities with an explicit
  `unknown` no-fit option. Generic text-classification pipelines and rules-only
  paths are not substitutes: option markers, head budget, and calibration are
  the decision head.
- [`expense_examples.json`](expense_examples.json) — labeled English expense
  examples: meaningful choices, no-fit/unknown cases, and boundary cases
  (mask-injection, long-state truncation, empty description).

Run (bundle is machine-local, never committed):

```powershell
python tools/laya/reference_runner.py --model-dir <bundle-dir> --examples tools/laya/expense_examples.json
```

`<bundle-dir>` holds the five pinned `receptron/laya-onnx` files at revision
`68f27dfe5a27a54fb2b1fefc432f43f972e90868` (see `PROVENANCE.md` for hashes).
Inference is offline; only the one-time bundle acquisition uses network.
Android runtime parity, latency, and memory are S4.2/S4.9 concerns, not claimed here.
