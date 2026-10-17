#!/usr/bin/env python3
"""S4.2 host helper: run caller-built tensors through the pinned Laya bundle.

The app's JVM tests build `input_ids`/`marker_pos` with their own tokenizer and
sequence port, then feed those exact tensors here so the real pinned weights sit in
the loop without ORT Java natives on the host (which this sandbox cannot load).
Only `session.run` executes here; sequence construction and output interpretation
stay in the code under test.

Input JSON: {"input_ids": [...], "markers": [...]}.
Output JSON: {"logits": [...], "act_probs": [...]}.

Usage:

    python tools/laya/run_tensors.py --model-dir <bundle-dir> \\
        --tensors <tensors.json> --output <logits.json>
"""

from __future__ import annotations

import argparse
import json
import os
import sys


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Run caller-built Laya tensors")
    parser.add_argument("--model-dir", required=True, help="local ONNX bundle directory")
    parser.add_argument("--tensors", required=True, help="input tensors JSON")
    parser.add_argument("--output", required=True, help="output logits JSON path")
    args = parser.parse_args(argv)

    for name in ("laya.onnx", "laya.onnx.data"):
        if not os.path.isfile(os.path.join(args.model_dir, name)):
            raise SystemExit(f"model bundle incomplete: {name} missing under {args.model_dir}")
    with open(args.tensors, encoding="utf-8") as fh:
        payload = json.load(fh)
    input_ids: list[int] = payload["input_ids"]
    markers: list[int] = payload["markers"]

    import numpy as np
    import onnxruntime as ort

    length = len(input_ids)
    options = len(markers)
    session = ort.InferenceSession(
        os.path.join(args.model_dir, "laya.onnx"), providers=["CPUExecutionProvider"]
    )
    logits, act = session.run(
        ["logits", "act_probs"],
        {
            "input_ids": np.array([input_ids], dtype=np.int64),
            "attention_mask": np.ones((1, length), dtype=np.int64),
            "marker_pos": np.array([markers], dtype=np.int64),
            "marker_mask": np.ones((1, options), dtype=bool),
            "qtype": np.array([0], dtype=np.int64),
        },
    )
    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "logits": [float(v) for v in logits[0, :options]],
                "act_probs": [float(v) for v in act[0]],
            },
            fh,
        )
        fh.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
