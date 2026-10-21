# SLM provenance (S5.1)

Pinned identities, revisions, licenses, and hashes for the Sprint 5 offline
assistant model evaluation, plus exact build/run pins for the on-device runner.
Every value below was read from the primary source named next to it; nothing is
The legacy benchmark remains partial, paused, and unapproved; no model was adopted. Machine-readable reports and raw device outputs under `artifacts/sprint-5/workers/` are preserved locally but intentionally unversioned.

## Candidate identities (verified from the Hugging Face Hub API on 2026-10-19)

| Requested name | Exact official identity | Exists? | License | Revision | Note |
| --- | --- | --- | --- | --- | --- |
| LFM2.5 350M | `LiquidAI/LFM2.5-350M` (+ official `-GGUF`) | yes | `lfm1.0` (custom, `license: other`) | `9e6c6ccf47cd318696e137d381a7ded8fe4df09f` | instruction-tuned, text-only, 16 layers, 32k context, 65,536 vocab |
| "LFM2.5 700M" | — | **no** | — | — | No `LFM2.5-700M` repository exists in the `LiquidAI` organization. The LFM2.5 compact line is **230M / 350M / 1.2B / 2.6B** (plus 8B-A1B, 24B-A2B, VL/audio/encoder variants). 700M exists only in the **previous** generation: `LiquidAI/LFM2-700M` (+ `LiquidAI/LFM2-700M-GGUF`). |
| LFM2.5 1.2B | `LiquidAI/LFM2.5-1.2B-Instruct` (+ official `-GGUF`) | yes | `lfm1.0` | `0f604ada3f766f9f257460c4c9f0b5d6f69d431b` | instruct model; `-Thinking`, `-Base`, and JP variants also exist |
| Qwen3.5 0.8B | `Qwen/Qwen3.5-0.8B` (instruct; `-Base` = `dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68`) | yes | `apache-2.0` | `2fc06364715b967f1860aea9cf38778875588b17` | **multimodal** VLM (`Qwen3_5ForConditionalGeneration`, `image-text-to-text`), 873,438,784 BF16 parameters, no BOS token. **No official Qwen GGUF exists** (`Qwen/Qwen3.5-0.8B-GGUF` → HTTP 401/absent); GGUF conversion used here is third-party `unsloth/Qwen3.5-0.8B-GGUF`. |
| gemma4-e2b | `google/gemma-4-E2B` and `google/gemma-4-E2B-it` | yes | `apache-2.0` | it: `3e22461f65e89153144f8adb70e3b8c2cc9845a7`; base: `d29ff6b45f081a49ee2733a859c9c9c2d95d1a6f` | on-device/edge "effective 2B" family. **No official Google GGUF**: Google ships LiteRT-LM (`litert-community/gemma-4-E2B-it-litert-lm`, not llama.cpp) and safetensors only. The GGUF used here is third-party `lmstudio-community/gemma-4-E2B-it-GGUF`. |

### Quantized files actually evaluated (size and SHA-256 from the Hub file list)

| File | Bytes | SHA-256 | Upstream repo revision |
| --- | --- | --- | --- |
| `LFM2.5-350M-Q4_0.gguf` | 219,309,792 | `85e32858daafad55b7bcd6b97a1343ee0661188e8036f9862d14d6b563142f50` | `LiquidAI/LFM2.5-350M-GGUF` @ `657e078c94084481950a2d555a941481f715536b` |
| `LFM2.5-350M-Q4_K_M.gguf` | 229,312,224 | `7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4` | same |
| `LFM2.5-350M-QAD-Q4_0.gguf` | 219,312,832 | `3d10b6ab8fc91a919534b9558e266255aca0bbc7f6d015963599aa9e74e05b1d` | same (quantization-aware distilled 4-bit) |
| `LFM2.5-1.2B-Instruct-Q4_0.gguf` | 695,751,488 | `2ea801949d760cdf1a2cc04a54262c22c3c0c54f0769d57760c9adeb0e59233f` | `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` @ `8ed288026e23958ad9dfa92d53ed773a8eee7125` |
| `LFM2.5-1.2B-Instruct-Q4_K_M.gguf` | 730,895,168 | `b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5` | same |
| `LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf` | 695,755,488 | `bb741ebb106d543e9de114b843a3d3d73d51c74b5801e69da2abde821a0cb3e1` | same |
| `Qwen3.5-0.8B-Q4_0.gguf` | 507,154,688 | `444406ddd926550c724ec18d5120a9d40ded44908a063b0e66e9a7e5464c652c` | `unsloth/Qwen3.5-0.8B-GGUF` @ `6ab461498e2023f6e3c1baea90a8f0fe38ab64d0` |
| `Qwen3.5-0.8B-Q4_K_M.gguf` | 532,517,120 | `bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517` | same |
| `gemma-4-E2B-it-Q4_K_M.gguf` | 3,427,880,384 | `71e6e8cb64a76da1a734fb8f6ba389d2784950d9902f5e87244fca0a15190c94` | `lmstudio-community/gemma-4-E2B-it-GGUF` @ `eed445ccfb8aef2e279df1a97e133baf06e443b4` |

Verified locally with the repository's `gguf-py` reader: `LFM2.5-350M-Q4_K_M.gguf`
carries `general.architecture = lfm2`, `bos_token_id = 1`, `eos_token_id = 7`,
`add_bos_token = true`, an embedded chat template, and `general.file_type = 15`
(Q4_K_M); `Qwen3.5-0.8B-Q4_K_M.gguf` carries `general.architecture = qwen35`,
`eos_token_id = 248046`, no BOS, an embedded chat template, and
`general.file_type = 15`.

## Runtime pin (llama.cpp on Android arm64-v8a)

| Item | Value | Source |
| --- | --- | --- |
| Runtime | `ggml-org/llama.cpp` commit `31288c9c66e59377f7788d920678a565bb89dd37` (`Sun Oct 18 18:08:11 2026`, build `b11723`) | `git log -1` in the clone, `git describe --tags` |
| Architecture support | `LLM_ARCH_LFM2`, `LLM_ARCH_LFM2MOE`, `LLM_ARCH_QWEN35`, `LLM_ARCH_QWEN35MOE`, `LLM_ARCH_GEMMA4` all present in `src/llama-arch.h` at this commit | source at the pinned commit |
| Android build recipe | `docs/build.md` §"Android arm64-v8a NDK build" | source at the pinned commit |
| NDK | `28.2.13676358` (r28c) installed with `sdkmanager --install "ndk;28.2.13676358"` | NDK `source.properties` |
| Configure flags | `-DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DGGML_CPU_KLEIDIAI=ON -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DBUILD_SHARED_LIBS=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=ON` | `docs/build.md` plus the static/single-binary options added for a push-only runner |
| Runner tool | `llama-completion` (`-no-cnv -f <prompt-file> -sp --perf`) | `tools/completion/README.md` at the pinned commit |
| Sampler settings | `--temp 0.1 --top-k 50 --repeat-penalty 1.05 --seed 42` (Liquid AI's recommended generation parameters for LFM2.5) | `LiquidAI/LFM2.5-350M` model card |

The LFM2.5 default sampler values are not stored in the GGUF metadata
(`general.sampling.*` is absent), so the evaluation passes them explicitly and
S5.2 must do the same.

## Rejected or unavailable names (no silent substitution)

* **"LFM2.5-700M"** — no such checkpoint exists. Treated as *unavailable*, not as
  `LFM2-700M`.
* **"gemma4-e2b" official GGUF** — Google's on-device artifact for Gemma 4 E2B is
  LiteRT-LM (`.litertlm`, LiteRT runtime), which is a different runtime family and
  is not a llama.cpp model. Any llama.cpp evaluation of Gemma 4 E2B therefore uses a
  third-party GGUF conversion and is labelled as such.
* **Official Qwen GGUF for Qwen3.5-0.8B** — absent from the `Qwen` organization at the
  time of writing; the evaluated conversion is third-party (`unsloth`).
* **Deprecated LEAP SDK** — not used anywhere in this evaluation.

## Licenses and redistribution

`LFM2.5` weights are released under the custom **LFM Open License v1.0** (`license: other`);
`Qwen3.5-0.8B` and `Gemma 4` weights are **Apache-2.0**. No model binary is committed to
this repository; only hashes, sizes, and upstream revisions are recorded. Redistributing
the LFM2.5 GGUF inside an APK would need a separate license review; the planned app flow
keeps acquisition deliberate and outside the repository.

## Candidate under corrected-contract evaluation (not adopted) (2026-10-19)

Qwen3.5-0.8B Q4_K_M was the strongest candidate in the first sweep, but the corrected-contract a3 full suite scored 18/23 and the held-out set 6/8; wrong tool/filter choices and clarification failures remain. This is a reproducible evaluation pin, not an adoption decision. No model has been demonstrated to meet all required behaviors, and S5.2 must not proceed as if Qwen is qualified. The a3 result is detailed below.

| Item | Reproducible evaluation value |
| --- | --- |
| Model | `unsloth/Qwen3.5-0.8B-GGUF` file `Qwen3.5-0.8B-Q4_K_M.gguf`, 532,517,120 bytes, SHA-256 `bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517`, upstream repo revision `6ab461498e2023f6e3c1baea90a8f0fe38ab64d0` |
| Base weights | `Qwen/Qwen3.5-0.8B` @ `2fc06364715b967f1860aea9cf38778875588b17`, Apache-2.0, 873,438,784 BF16 parameters, tokenizer `Qwen3.5-0.8B` |
| GGUF architecture | `qwen35`, `eos_token_id = 248046`, no BOS token, embedded chat template, `general.file_type = 15` |
| Runtime | `ggml-org/llama.cpp` `31288c9c66e59377f7788d920678a565bb89dd37` (`b11723`), NDK `28.2.13676358`, Android `arm64-v8a`, KleidiAI enabled |
| Chat/sampling | prompt ends with `<|im_start|>assistant\n<think>\n\n</think>\n\n` (thinking disabled); `--temp 0.1 --top-k 50 --repeat-penalty 1.05` |
| Fallback candidate | `LiquidAI/LFM2.5-350M-GGUF` file `LFM2.5-350M-Q4_K_M.gguf`, SHA-256 `7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4` |

Caveats attached to this unadopted candidate:

* There is **no official Qwen GGUF** for Qwen3.5-0.8B; this conversion is third-party. The
  base weights are Apache-2.0, but the conversion itself has no upstream Qwen review.
* The model is a vision-language checkpoint used text-only in this evaluation.
* The first contract-v1 sweep had 0/7 tool cases with correct tool + arguments; it exposed
  missing intent routing and conflicting epistemic instructions in the prompt. Grammar
  alone does not determine which tool matches user intent. The benchmark remains unapproved
  (see the historical limitations in [`README.md`](README.md)).
* Under the corrected prompt/tools, the a3 frozen 23-case suite graded 18/23 and the
  8-case held-out suite 6/8. Exact tool + required-argument correctness was 5/7 and 4/5,
  respectively. Good examples include `tl-01`–`tl-03`, `ml-01` (`Dining` from Vietnamese),
  `ho-01`–`ho-03`, and `ho-07` (`Transport` from Vietnamese).
* Required semantics still fail: `tl-04` chose `sum_expenses` over bounded lookup;
  `tl-05`/`ho-08` guessed a month for missing date scope; `tl-11` called an expense sum
  for a transfer; `ml-02` omitted its category; `ho-04` omitted the ride category and set
  `type="transfer"`; `cn-07` guessed rather than clarifying. The grader also falsely passed
  `tl-07` although raw output continued after a SQL refusal with an incomplete unrelated
  `sum_expenses` call, and falsely passed `ho-05` although it offered unrelated groceries
  instead of asking which listed category the user meant.
* The pre-fix a3 report's peak HWM/sample-count/available-memory fields included old rows:
  `device_probe.sh` appended to reused `samples.csv`, and the evaluator summarized the whole
  file. Isolated current-run windows show 697,872–724,456 kB HWM for the frozen cases and
  724,328–724,424 kB held out; the reported 3.4–3.6 GB maxima are not current-run memory.
  See `S5.1-a3-memory-analysis.json` and the preserved raw CSVs. The evaluator now clears
  samples per probe, namespaces device run/prompt paths by report stem, and records the
  probe hash. A post-fix device smoke measured 680,744 kB load HWM, 724,424 kB case HWM,
  and 710,912 kB cancellation HWM; SIGINT exited 130 in 350 ms with fresh counts matching
  status files.
* This corrected-contract run tested Qwen only. Earlier alternative-candidate results are
  retained for their measured identity, naming, safety, and resource comparisons, but
  do not prove those models under the corrected prompt. No adoption is justified by a
  syntax-shaped call or an overall score; a fresh evidence review is still required.
* The a2 preliminary corrected-contract prompt used ~1,850 tokens and 67–82 s prefill per
  tool case versus ~958 tokens/~40 s under v1. In a3, median prefill throughput was
  23.45 tok/s on the frozen suite and 18.91 tok/s held out, with median decode 8.86 and
  7.82 tok/s. These are fresh-process measurements; S5.2/S5.3 must separately evaluate
  held-warm session behavior and prompt-prefix reuse.

## LFM2.5-1.2B-Instruct input rendering (S5.1-a4)

The renderer follows the relevant one-system-message, tools-present, user-message,
assistant-generation-prefix branch of LiquidAI's official template at
[`LiquidAI/LFM2.5-1.2B-Instruct@0f604ada`](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct/raw/0f604ada3f766f9f257460c4c9f0b5d6f69d431b/chat_template.jinja).
It preserves system and user content verbatim and uses the template's `tojson`
serialization behavior for tool definitions. This path does not manually prepend BOS:
the pinned GGUF's `tokenizer.ggml.add_bos_token=true` is honored by llama.cpp's
completion tokenizer.

Local GGUF metadata, read with the repository `gguf-py` reader for
`LFM2.5-1.2B-Instruct-Q4_K_M.gguf` (730,895,168 bytes, SHA-256
`b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5`), reports
`general.architecture=lfm2`, `add_bos_token=true`, and an embedded
`tokenizer.chat_template` of 1,783 characters with SHA-256
`f05bf4b967dc993bdc7a2fe6e43759ee218eb0eb340d68b063e1c4f8ad148176`. The embedded
template's input-rendering branch matches the branch used by the renderer. This is
format provenance only; it does not imply semantic tool correctness or adoption.

## LFM2.5-350M input rendering (S5.1-a4)

The 350M renderer uses the same relevant one-system-message, tools-present,
user-message, assistant-generation-prefix branch, verified against LiquidAI's official
template pinned at
[`LiquidAI/LFM2.5-350M@9e6c6ccf`](https://huggingface.co/LiquidAI/LFM2.5-350M/raw/9e6c6ccf47cd318696e137d381a7ded8fe4df09f/chat_template.jinja).
The source also begins with `bos_token`; the pinned Q4_K_M GGUF metadata records
`add_bos_token=true`, so llama.cpp supplies BOS rather than the host renderer.
Evaluation report provenance must use the model-specific 350M source revision, not the
1.2B revision. This is rendering provenance, not a semantic result or tier claim.

## Gemma 4 E2B-it template provenance (S5.1-a4)

The canonical Google Gemma 4 template is pinned to
[`google/gemma-4-E2B-it@3e22461f`](https://huggingface.co/google/gemma-4-E2B-it/raw/3e22461f65e89153144f8adb70b5d6f69d431b/chat_template.jinja).
For the locally evaluated third-party `gemma-4-E2B-it-Q4_K_M.gguf` (3,427,880,384
bytes, SHA-256
`71e6e8cb64a76da1a734fb8f6ba389d2784950d9902f5e87244fca0a15190c94`), `gguf-py`
reports `general.architecture=gemma4`, `tokenizer.ggml.bos_token_id=2`,
`tokenizer.ggml.add_bos_token=true`, and an embedded
`tokenizer.chat_template` 18,566 characters long with SHA-256
`603a42db292c25278e9b23d94c7abbe77453f13e26a053c43c5c2f900b4136f7`. The embedded
system/tool/user/generation-prompt branch matches the pinned Google source. The faithful
host renderer and native argument parser must follow that branch before any corrected
Gemma accuracy result is reported; the bounded a1 run was resource evidence only.
