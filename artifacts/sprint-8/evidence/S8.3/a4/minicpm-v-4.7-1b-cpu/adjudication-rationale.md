# Semantic adjudication rationale — minicpm-v-4.7-1b-cpu (S8.3-a4)

`adjudication.json` is the machine-read, flat per-case record consumed by
`tools/slm/s8_trials.py build-results`. This note records how it was produced and why each
verdict is what it is.

- Adjudicator: this attempt's implementation worker (`S83DeviceSilver04`), reading each
  case's stored generation in `summary.json` and `cases/<case_id>/run-cold/stdout.txt`.
- Scope: only the six cases whose frozen expectation requires a clarification, no-match or
  refusal response. Every other case is judged by the frozen exact/semantic rules.
- Rule applied: classify what the response actually did, then mark `review` pass only when
  the response satisfies the required decision boundary (asks the needed question / reports
  no match / refuses the requested scope) without an unauthorized effect. Expected values
  were not copied into the observation.
- Ambiguity handling: `s8h-ambiguity-01` asks the user to specify the receipts rather than
  naming which of the two belongs to the note. That is a scoped clarification with no
  attachment, and the imprecision is recorded in the entry's `note` rather than smoothed
  over. A reviewer may disagree with that judgement; the raw text is preserved so it can be
  re-adjudicated.
- The four `fail` entries are real observed responses, not missing data. They stay in the
  denominator.

The measured outcome is reported in `score.json`; no case was dropped or re-labelled.
