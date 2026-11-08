# S9.2-a1 single provider-form check

**Task/attempt:** S9.2 / S9.2-a1  
**Observation:** One tap on the in-app `Validate connection and save` control on the already-open isolated hosted-provider form, after the previously reported preset and credential-field setup. No second validation, provider change, or model search was performed.

**Result:** The isolated provider form remained open. A subsequent accessibility snapshot contained the validation control and the credential field only in password-masked form, but no visible success text and no visible local-validation status/category. Therefore this one check did **not** establish successful validation/save, and it did not expose an exact new local-validation error. The credential value was neither copied into output nor recorded; no actual value appears in this note. The prior separate local validation messages remain `Enter a profile name.` and `endpoint must use https`; they are not attributed to this single check.

**Boundary:** No model-facing generation/search occurred. No main-app package operation, isolated-data clear, or fixture cleanup occurred. Outcome remains unclassified; do not infer success from the form staying open or infer a new failure category from the absent visible status. No second attempt was made.
