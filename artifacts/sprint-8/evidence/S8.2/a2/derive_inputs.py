#!/usr/bin/env python3
"""Derive S8.2-a2 scorer inputs from the preserved S8.3-a2 contract discriminator.

The S8.3-a2 result documents predate the v2 contract: they carry no contract
binding and no per-call stored-reading evidence. This script reads them
read-only, copies them, adds the current frozen v2 binding, and derives
``call_effects`` from the preserved real ``WorkflowCommandService`` trace.
Nothing produced here is model output or a new device run.
"""
from __future__ import annotations

import copy
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
sys.path.insert(0, str(ROOT / "tools/slm"))

import s8_contract  # noqa: E402

SOURCE = ROOT / "artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator"
OUT = HERE / "inputs"
PROBES = OUT / "probes"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stored_reading(trace: dict[str, Any]) -> dict[str, Any]:
    rows = trace["database_state"]["captures_events"]
    if len(rows) != 1:
        raise SystemExit(f"expected exactly one stored reading, found {len(rows)}")
    row = rows[0]
    return {
        "capture_id": row["capture_id"],
        "span_start": row["span_start"],
        "span_end": row["span_end"],
        "normalized_text": row["normalized_text"],
        "amount_vnd": row["amount_vnd"],
        "anchor_date": row["anchor_date"],
        "anchor_time": row["anchor_time"],
        "unresolved_reason": row.get("unresolved_reason"),
        "status": row["status"],
    }


def base_document(results_name: str, trace_name: str, label: str) -> dict[str, Any]:
    results = json.loads((SOURCE / results_name).read_text(encoding="utf-8"))
    trace = json.loads((SOURCE / trace_name).read_text(encoding="utf-8"))
    record = copy.deepcopy(results["results"][0])
    record["call_effects"] = [{"call_index": 0, "stored_reading": stored_reading(trace)}]
    record["binding"] = s8_contract.current_binding()
    return {
        "profile": results["profile"],
        "sample_provenance": {
            "origin": "host_constructed_diagnostic",
            "not_model_output": True,
            "not_a_new_device_run": True,
            "label": label,
            "source_results": f"artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator/{results_name}",
            "source_trace": f"artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator/{trace_name}",
            "source_sha256": {
                results_name: sha256(SOURCE / results_name),
                trace_name: sha256(SOURCE / trace_name),
            },
            "note": (
                "The calls were constructed by the S8.3-a2 contract discriminator and dispatched "
                "through the real WorkflowCommandService; this document is a scoring diagnostic, "
                "not a candidate score, and cannot qualify a profile."
            ),
        },
        "binding": s8_contract.current_binding(),
        "results": [record],
        "metrics": {},
    }


def write(name: str, document: dict[str, Any], directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{name}.json").write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def call(document: dict[str, Any]) -> dict[str, Any]:
    return document["results"][0]["calls"][0]


def interpretation(document: dict[str, Any]) -> dict[str, Any]:
    return call(document)["args"]["interpretation"]


def stored(document: dict[str, Any]) -> dict[str, Any]:
    return document["results"][0]["call_effects"][0]["stored_reading"]


def mutation(name: str, reason: str, apply: Any) -> tuple[str, str, Any]:
    return name, reason, apply


def m_empty_key(document: dict[str, Any]) -> None:
    call(document)["args"]["idempotencyKey"] = ""


def m_whitespace_key(document: dict[str, Any]) -> None:
    call(document)["args"]["idempotencyKey"] = "   "


def m_changed_scope(document: dict[str, Any]) -> None:
    call(document)["args"]["captureId"] = 103


def m_changed_revision(document: dict[str, Any]) -> None:
    call(document)["args"]["expectedCaptureRevision"] = 1


def m_changed_amount(document: dict[str, Any]) -> None:
    interpretation(document)["amountVnd"] = 70001


def m_changed_date(document: dict[str, Any]) -> None:
    interpretation(document)["timeAnchor"]["date"] = "2026-10-23"


def m_changed_time(document: dict[str, Any]) -> None:
    interpretation(document)["timeAnchor"]["time"] = "09:31"


def m_changed_intent(document: dict[str, Any]) -> None:
    document["results"][0]["calls"] = [
        {
            "name": "query_draft_candidates",
            "args": {
                "query": {
                    "reviewStatus": "PENDING",
                    "onlyPossibleDuplicates": False,
                    "bounds": {"limit": 5, "offset": 0},
                }
            },
        }
    ]


def m_extra_call(document: dict[str, Any]) -> None:
    document["results"][0]["calls"].append(
        {
            "name": "query_draft_candidates",
            "args": {
                "query": {
                    "reviewStatus": "PENDING",
                    "onlyPossibleDuplicates": False,
                    "bounds": {"limit": 1, "offset": 0},
                }
            },
        }
    )


def m_missing_effects(document: dict[str, Any]) -> None:
    document["results"][0].pop("effects")


def m_uninstrumented(document: dict[str, Any]) -> None:
    document["results"][0]["effect_source"] = "application_trace_unavailable"


def _set_text(document: dict[str, Any], text: str) -> None:
    interpretation(document)["normalizedText"] = text
    stored(document)["normalized_text"] = text


def m_nonsense_text(document: dict[str, Any]) -> None:
    _set_text(document, "zqxv blorf")


def m_fabricated_numbers(document: dict[str, Any]) -> None:
    _set_text(document, "Refund 999,000 VND at 23:59")


def m_text_not_stored(document: dict[str, Any]) -> None:
    stored(document)["normalized_text"] = "Book purchase"


def m_missing_stored_reading(document: dict[str, Any]) -> None:
    document["results"][0].pop("call_effects")


def m_extra_argument(document: dict[str, Any]) -> None:
    call(document)["args"]["evidence"] = "caller supplied"


def m_unbound(document: dict[str, Any]) -> None:
    document.pop("binding")
    document["results"][0].pop("binding")


def m_foreign_binding(document: dict[str, Any]) -> None:
    document["binding"]["inputs"]["tools"] = "0" * 64
    document["results"][0]["binding"]["inputs"]["tools"] = "0" * 64


def _set_reason(document: dict[str, Any], reason: str) -> None:
    interpretation(document)["unresolvedReason"] = reason
    stored(document)["unresolved_reason"] = reason
    stored(document)["status"] = "UNRESOLVED"


def m_contradicted_reason(document: dict[str, Any]) -> None:
    _set_reason(document, "TIME_NOT_STATED")


def m_long_reason(document: dict[str, Any]) -> None:
    _set_reason(document, "R" * 130)


def m_long_extraction_version(document: dict[str, Any]) -> None:
    call(document)["args"]["extractionVersion"] = "v" * 70


def m_parse_error(document: dict[str, Any]) -> None:
    document["results"][0]["parse_error"] = True


MUTATIONS = (
    mutation("mut-empty-key", "idempotency_key_invalid", m_empty_key),
    mutation("mut-whitespace-key", "idempotency_key_invalid", m_whitespace_key),
    mutation("mut-changed-scope", "call_intent_or_arguments_mismatch", m_changed_scope),
    mutation("mut-changed-revision", "call_intent_or_arguments_mismatch", m_changed_revision),
    mutation("mut-changed-amount", "call_intent_or_arguments_mismatch", m_changed_amount),
    mutation("mut-changed-date", "call_intent_or_arguments_mismatch", m_changed_date),
    mutation("mut-changed-time", "call_intent_or_arguments_mismatch", m_changed_time),
    mutation("mut-changed-intent", "call_intent_or_arguments_mismatch", m_changed_intent),
    mutation("mut-extra-call", "call_intent_or_arguments_mismatch", m_extra_call),
    mutation("mut-missing-effects", "observed_effect_mismatch", m_missing_effects),
    mutation("mut-uninstrumented", "application_effect_trace_missing", m_uninstrumented),
    mutation("mut-nonsense-text", "normalized_text_not_source_backed", m_nonsense_text),
    mutation("mut-fabricated-numbers", "normalized_text_fabricated_facts", m_fabricated_numbers),
    mutation("mut-text-not-stored", "normalized_text_effect_mismatch", m_text_not_stored),
    mutation("mut-missing-stored-reading", "stored_reading_evidence_missing", m_missing_stored_reading),
    mutation("mut-extra-argument", "call_schema_invalid", m_extra_argument),
    mutation("mut-unbound", "contract_binding_missing", m_unbound),
    mutation("mut-foreign-binding", "contract_binding_mismatch", m_foreign_binding),
    mutation("mut-contradicted-reason", "unresolved_reason_decision_mismatch", m_contradicted_reason),
    mutation("mut-long-reason", "unresolved_reason_label_invalid", m_long_reason),
    mutation("mut-long-extraction-version", "extraction_version_invalid", m_long_extraction_version),
    mutation("mut-parse-error", "parse_error", m_parse_error),
)


def main() -> int:
    semantic = base_document(
        "results-semantic-variant.json",
        "trace-semantic-variant.json",
        "legitimate different key and reading, same observed effect",
    )
    control = base_document(
        "results-exact-literal-control.json",
        "trace-exact-literal-control.json",
        "exact-literal control from the S8.3-a2 discriminator",
    )
    write("semantic-variant", semantic, OUT)
    write("exact-literal-control", control, OUT)
    alternate = copy.deepcopy(semantic)
    alternate["sample_provenance"]["label"] = "same reading with a different declared extraction version"
    alternate["results"][0]["calls"][0]["args"]["extractionVersion"] = "producer 2026-10"
    write("semantic-variant-alt-producer", alternate, OUT)
    empty = {
        "profile": "s8.2-rubric-v2-diagnostic",
        "sample_provenance": {"origin": "host_constructed_diagnostic", "not_model_output": True},
        "binding": s8_contract.current_binding(),
        "results": [],
        "metrics": {},
    }
    write("empty-results", empty, OUT)
    manifest = []
    if PROBES.exists():
        for stale in PROBES.glob("*.json"):
            if stale.name != "manifest.json":
                stale.unlink()
    for name, reason, apply in MUTATIONS:
        document = copy.deepcopy(semantic)
        apply(document)
        write(name, document, PROBES)
        manifest.append({"input": f"probes/{name}.json", "expected_reason": reason})
    (PROBES / "manifest.json").write_text(
        json.dumps(
            {
                "derivation": "each probe starts from inputs/semantic-variant.json and applies one mutation",
                "diagnostic_only": "scorer mutation probes; effects are the preserved S8.3-a2 diagnostic values",
                "probes": manifest,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"status": "PASS", "base_inputs": 4, "probes": len(manifest)}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
