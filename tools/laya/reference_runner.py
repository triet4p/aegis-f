#!/usr/bin/env python3
"""S4.1 reference runner: English Laya category decisions through the real ONNX artifact.

Reference semantics (primary sources, pinned in PROVENANCE.md):
  convaiinnovations/laya `rl_common.py` (build_sequence / render_options /
  serialize_state / temp_bucket / confidence_from_probs / DecisionModel.forward)
  and `rl_agent_api.py` (RLAgent.system_one), as exported by receptron/laya
  `export/export_onnx.py` and executed by receptron/laya `src/sequence.ts` +
  `src/laya.ts`.

The runner reproduces, in Python, exactly the export bundle's contract:

  sequence:  [CLS] <type> question: instructions [SEP] [MASK] opt0 [MASK] opt1 ...
             [SEP] state [SEP]
  inputs:    input_ids/attention_mask [B,L] int64, marker_pos [B,K] int64,
             marker_mask [B,K] bool, qtype [B] int64 (choice=0, score=1, noul=2)
  outputs:   logits [B,K] float32 raw (masked slots = -1e4),
             act_probs [B,2] float32 (softmax already applied in-graph)
  decision:  softmax(logits[k] / T(temp_bucket(qtype, k))); Jev confidence
             1 - H(p)/ln(k); round 4 like the reference API.

For this task the question is always one `choice` question: "which existing
category fits this expense description?", with an explicit `unknown` no-fit
option so the model can decline instead of forcing a category.

Usage (model bundle is machine-local, never committed; see PROVENANCE.md):

  python tools\\laya\\reference_runner.py --model-dir <bundle> \\
      --examples tools\\laya\\expense_examples.json [--output out.json]

Offline: needs only the local bundle directory. No network, no Android.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from typing import Any


QTYPE_IDS = {"choice": 0, "score": 1, "noul": 2}
QTYPE_NAMES = ["choice", "score", "noul"]

MASK_TOKEN = "[MASK]"
UNKNOWN_OPTION = "unknown"
UNKNOWN_DESCRIPTION = "none of the listed categories fit this expense"


def load_bundle(model_dir: str) -> dict[str, Any]:
    """Read the export bundle config + tokenizer (weights stay in onnxruntime)."""
    for name in ("laya.onnx", "laya_config.json", "tokenizer/tokenizer.json",
                 "tokenizer/tokenizer_config.json"):
        path = os.path.join(model_dir, name)
        if not os.path.isfile(path):
            raise SystemExit(
                f"model bundle incomplete: {name} missing under {model_dir}; "
                "see tools/laya/PROVENANCE.md for the pinned download"
            )
    with open(os.path.join(model_dir, "laya_config.json"), encoding="utf-8") as fh:
        config = json.load(fh)
    return {"dir": model_dir, "config": config}


def make_tokenizer(model_dir: str):
    from tokenizers import Tokenizer as HFTokenizer

    tok = HFTokenizer.from_file(os.path.join(model_dir, "tokenizer", "tokenizer.json"))
    ids = {}
    for special in ("[CLS]", "[SEP]", "[MASK]", "[PAD]"):
        token_id = tok.token_to_id(special)
        if token_id is None:
            raise SystemExit(f"bundle tokenizer lacks required special token {special}")
        ids[special] = token_id
    return tok, ids


def render_options(categories: list[dict[str, str]]) -> list[str]:
    """Choice option texts in label-index order: `name: description`, plus unknown."""
    options = []
    for category in categories:
        name = category["name"]
        description = (category.get("description") or "").strip()
        options.append(f"{name}: {description}" if description else name)
    options.append(f"{UNKNOWN_OPTION}: {UNKNOWN_DESCRIPTION}")
    return options


def serialize_state(description: str) -> str:
    # Plain-string states pass through verbatim (rl_common.serialize_state).
    return description


def temp_bucket(qtype: int, k: int) -> str:
    if k <= 2:
        size = "2"
    elif k <= 5:
        size = "3-5"
    elif k <= 10:
        size = "6-10"
    else:
        size = "11+"
    return f"{QTYPE_NAMES[qtype]}:{size}"


def confidence_from_probs(probs: list[float]) -> float:
    k = len(probs)
    if k < 2:
        return 1.0
    entropy = -sum(p * math.log(max(p, 1e-12)) for p in probs)
    return 1.0 - entropy / math.log(k)


def softmax(logits: list[float]) -> list[float]:
    peak = max(logits)
    exps = [math.exp(v - peak) for v in logits]
    total = sum(exps)
    return [v / total for v in exps]


def _scrub(text: str) -> str:
    """Replace the literal mask-token text so user text cannot inject a marker."""
    return text.replace(MASK_TOKEN, " ")


def build_sequence(
    encode,
    special_ids: dict[str, int],
    state: str,
    instructions: str,
    options: list[str],
    max_len: int,
    head_max_len: int,
) -> tuple[list[int], list[int]]:
    """Port of rl_common.build_sequence for one choice question (eval order)."""
    head_ids = encode(f"choice question: {_scrub(instructions)}")
    opt_ids = [
        [special_ids["[MASK]"]] + encode(" " + _scrub(opt))[:48] for opt in options
    ]
    opt_budget = head_max_len - sum(len(o) for o in opt_ids)
    if opt_budget < 16:  # too many / too long options: shrink every option evenly
        per = max(4, (head_max_len - 16) // max(1, len(opt_ids)))
        opt_ids = [o[:per] for o in opt_ids]
        opt_budget = head_max_len - sum(len(o) for o in opt_ids)
    head_ids = head_ids[: max(8, opt_budget)]
    ids = [special_ids["[CLS]"]] + head_ids + [special_ids["[SEP]"]]
    markers: list[int] = []
    for option in opt_ids:
        markers.append(len(ids))
        ids.extend(option)
    ids.append(special_ids["[SEP]"])
    room = max(0, max_len - len(ids) - 1)
    state_ids = encode(_scrub(serialize_state(state)))[:room]
    ids = ids + state_ids + [special_ids["[SEP]"]]
    ids = ids[:max_len]
    return ids, [m for m in markers if m < max_len]


def decide(
    session,
    tok,
    special_ids: dict[str, int],
    config: dict[str, Any],
    description: str,
    instructions: str,
    categories: list[dict[str, str]],
) -> dict[str, Any]:
    import numpy as np

    options = render_options(categories)
    encode = lambda text: tok.encode(text, add_special_tokens=False).ids  # noqa: E731
    ids, markers = build_sequence(
        encode, special_ids, description, instructions, options,
        config["max_len"], config["head_max_len"],
    )
    if len(markers) != len(options):
        return {
            "status": "over_budget",
            "reason": (
                f"only {len(markers)} of {len(options)} options fit in "
                f"head_max_len={config['head_max_len']}; candidate retrieval "
                "must narrow the set (S4.4) rather than silently truncate"
            ),
            "options": len(options),
            "markers": len(markers),
        }
    pad_id = special_ids["[PAD]"]
    length = len(ids)
    k = len(markers)
    input_ids = np.full((1, length), pad_id, dtype=np.int64)
    attention = np.zeros((1, length), dtype=np.int64)
    input_ids[0, :length] = np.array(ids, dtype=np.int64)
    attention[0, :length] = 1
    marker_pos = np.array([markers], dtype=np.int64)
    marker_mask = np.ones((1, k), dtype=bool)
    qtype = np.array([QTYPE_IDS["choice"]], dtype=np.int64)
    logits, act = session.run(
        ["logits", "act_probs"],
        {
            "input_ids": input_ids,
            "attention_mask": attention,
            "marker_pos": marker_pos,
            "marker_mask": marker_mask,
            "qtype": qtype,
        },
    )
    temperature = config["temperature_by_options"].get(
        temp_bucket(QTYPE_IDS["choice"], k), config["temperature"][0]
    )
    scaled = [float(v) / temperature for v in logits[0, :k]]
    probs = softmax(scaled)
    names = [c["name"] for c in categories] + [UNKNOWN_OPTION]
    best = max(range(k), key=lambda i: probs[i])
    return {
        "status": "decided",
        "choice": names[best],
        "probabilities": {name: round(p, 4) for name, p in zip(names, probs)},
        "confidence": round(confidence_from_probs(probs), 4),
        "temperature": temperature,
        "sequence_len": length,
        "options": k,
        "input_tokens": length,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Laya English expense-category reference decisions")
    parser.add_argument("--model-dir", required=True, help="local ONNX bundle directory")
    parser.add_argument("--examples", required=True, help="labeled expense examples JSON")
    parser.add_argument("--output", default="", help="write decisions JSON here (default: stdout)")
    parser.add_argument("--limit", type=int, default=0, help="run only the first N examples (0 = all)")
    args = parser.parse_args(argv)

    bundle = load_bundle(args.model_dir)
    with open(args.examples, encoding="utf-8") as fh:
        payload = json.load(fh)
    examples = payload["examples"]
    if args.limit > 0:
        examples = examples[: args.limit]

    import onnxruntime as ort

    session = ort.InferenceSession(
        os.path.join(bundle["dir"], "laya.onnx"), providers=["CPUExecutionProvider"]
    )
    tok, special_ids = make_tokenizer(bundle["dir"])

    decisions = []
    for example in examples:
        result = decide(
            session, tok, special_ids, bundle["config"],
            example["description"], payload["question"]["instructions"],
            payload["categories"],
        )
        decisions.append({"id": example["id"], "expected": example.get("expected"), **result})

    report = {
        "model": "english-laya-onnx",
        "max_len": bundle["config"]["max_len"],
        "head_max_len": bundle["config"]["head_max_len"],
        "decisions": decisions,
    }
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
