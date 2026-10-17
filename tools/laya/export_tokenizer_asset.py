#!/usr/bin/env python3
"""S4.2 host tool: export the pinned Laya tokenizer into a compact binary asset.

Reads the pinned ``tokenizer/tokenizer.json`` from a local export bundle directory
(machine-local, never committed) and writes a deterministic binary tokenizer asset
for the Android runtime plus a JSON report with hashes.

Binary layout (all integers big-endian to match Java DataInputStream order, all strings UTF-8 bytes):

``laya_tokenizer.bin``::

    magic      8 bytes  ``b'LAYATOK1'``
    vocab_size u32      number of BPE vocab entries (ids 0..vocab_size-1)
    per id:    u32 byte-length + raw bytes of the vocab token string
    merge_count u32
    per merge: u32(left_len) + left bytes + u32(right_len) + right bytes
               (rank order preserved: index in file is the merge rank)
    added_count u32
    per added token: u32(id) + u32(byte-length) + raw bytes of content
                     + u8 flags bit0=lstrip bit1=rstrip bit2=single_word
                     + bit3=special bit4=normalized

Vocab ids are positional (entry ``i`` carries id ``i``); the writer verifies
that ``vocab[token] == index`` for every entry so the asset is self-consistent.

Usage:

    python tools/laya/export_tokenizer_asset.py --model-dir <bundle-dir> \\
        --asset app/src/main/assets/laya/laya_tokenizer.bin \\
        --report artifacts/sprint-4/workers/S4.2-A1-tokenizer-report.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys

MAGIC = b"LAYATOK1"


def load_tokenizer_json(model_dir: str) -> dict:
    path = os.path.join(model_dir, "tokenizer", "tokenizer.json")
    if not os.path.isfile(path):
        raise SystemExit(
            f"tokenizer.json missing under {model_dir}; see tools/laya/PROVENANCE.md"
        )
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def build_asset(payload: dict) -> tuple[bytes, dict]:
    model = payload["model"]
    if model.get("type") != "BPE":
        raise SystemExit("pinned tokenizer is not a BPE model; refusing to export")
    vocab: dict[str, int] = model["vocab"]
    merges: list[list[str]] = model["merges"]
    added: list[dict] = payload.get("added_tokens", [])

    by_id: list[str | None] = [None] * len(vocab)
    for token, token_id in vocab.items():
        if not 0 <= token_id < len(vocab):
            raise SystemExit(f"vocab id out of range: {token_id}")
        if by_id[token_id] is not None:
            raise SystemExit(f"duplicate vocab id: {token_id}")
        by_id[token_id] = token
    missing = [i for i, token in enumerate(by_id) if token is None]
    if missing:
        raise SystemExit(f"vocab ids missing: {missing[:8]}")

    out = bytearray(MAGIC)
    out += struct.pack(">I", len(by_id))
    for token in by_id:
        raw = token.encode("utf-8")  # type: ignore[union-attr]
        out += struct.pack(">I", len(raw))
        out += raw
    out += struct.pack(">I", len(merges))
    for left, right in merges:
        left_raw = left.encode("utf-8")
        right_raw = right.encode("utf-8")
        out += struct.pack(">I", len(left_raw))
        out += left_raw
        out += struct.pack(">I", len(right_raw))
        out += right_raw
    out += struct.pack(">I", len(added))
    for entry in sorted(added, key=lambda item: item["id"]):
        raw = entry["content"].encode("utf-8")
        flags = (
            (0x01 if entry.get("lstrip") else 0)
            | (0x02 if entry.get("rstrip") else 0)
            | (0x04 if entry.get("single_word") else 0)
            | (0x08 if entry.get("special") else 0)
            | (0x10 if entry.get("normalized") else 0)
        )
        out += struct.pack(">i", entry["id"])
        out += struct.pack(">I", len(raw))
        out += raw
        out += struct.pack("<B", flags)
    info = {
        "vocab_size": len(by_id),
        "merge_count": len(merges),
        "added_count": len(added),
        "asset_bytes": len(out),
        "asset_sha256": hashlib.sha256(bytes(out)).hexdigest(),
    }
    return bytes(out), info


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Export pinned Laya tokenizer asset")
    parser.add_argument("--model-dir", required=True, help="local ONNX bundle directory")
    parser.add_argument("--asset", required=True, help="output binary asset path")
    parser.add_argument("--report", required=True, help="output JSON report path")
    args = parser.parse_args(argv)

    payload = load_tokenizer_json(args.model_dir)
    blob, info = build_asset(payload)
    os.makedirs(os.path.dirname(os.path.abspath(args.asset)), exist_ok=True)
    with open(args.asset, "wb") as fh:
        fh.write(blob)
    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as fh:
        json.dump(info, fh, indent=2)
        fh.write("\n")
    print(json.dumps(info, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
