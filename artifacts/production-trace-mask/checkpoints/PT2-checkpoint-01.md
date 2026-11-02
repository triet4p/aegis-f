# PT2 Checkpoint Report

## Identity

- **task_id**: `PT2`
- **attempt_id**: `PT2-checkpoint-a1`
- **agent_id**: `MaskedFlowCheckpoint116`
- **target_ref**: `refs/heads/main`
- **repository**: `F:/ai-ml/aegis-f`
- **git_dir**: `F:/ai-ml/aegis-f/.git`
- **worktree**: `F:/ai-ml/aegis-f`

## Review Authorization

- **reviewer**: `MaskedFlowReview115`
- **review report**: `artifacts/production-trace-mask/reviews/PT2-review-03.md`
- **review manifest**: `artifacts/production-trace-mask/reviews/PT2-review-03.manifest.json`
- **review verdict**: `PASS`
- **snapshot_sha256**: `7d5fb04ad70aa1dd67efb40fa1bea47bee2ac88f3b85ed38e32dcf8f5bfd9d93`
- **worker artifact**: `artifacts/production-trace-mask/workers/PT2-a3.md`

## Pre-Commit Verification

- **expected base**: `6589c880537ee61a92fb99b91b70df5bc501930c`
- **actual base before commit**: `6589c880537ee61a92fb99b91b70df5bc501930c`
- **scope paths**: `58` (47 modified, 9 added, 2 deleted)
- **worktree raw SHA-256**: all 58 scoped paths matched `worktree_raw_sha256` in reviewer manifest.
- **base blob OIDs**: all 56 tracked base paths matched `base_blob_oid` in reviewer manifest; 2 added paths absent in base.
- **ownership/isolation**: no unrelated changes were staged before commit; scoped path set exactly matched manifest scope.

## Commit

- **commit SHA**: `8518676c3e8f7475c1a5140275224fe286cb4a92`
- **parent SHA**: `6589c880537ee61a92fb99b91b70df5bc501930c`
- **files changed reported by Git**: `56` (rename detection collapsed 2 deletions into renames)
- **hooks/signing/identity**: honored via standard `git commit`

## Post-Commit Verification

- **committed parent**: `6589c880537ee61a92fb99b91b70df5bc501930c` (matches expected base)
- **committed path set**: exactly the 58 manifest scope paths
- **committed content**: all 56 present paths matched reviewed worktree bytes after semantic CRLF-to-LF normalization where applicable; 2 deleted paths absent
- **CRLF normalization**: 16 scoped files had worktree CRLF normalized to LF in committed blobs; no other content differences
- **remaining staged changes**: `0`
- **scoped worktree status after commit**: clean

## Unrelated State Preserved

Unrelated modified/untracked repository state outside the 58-path scope was not staged or changed.
