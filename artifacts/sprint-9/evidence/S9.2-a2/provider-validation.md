# S9.2-a2 provider configuration and validation (no secret recorded)

Attempt `S9.2-a2`, isolated package `io.github.triet4p.aegisf.s812evidence`. The configured provider was
created only through the app's own `HostedProviderSettingsScreen`; no settings file was edited and no
validation/consent step was bypassed.

## Input method state (recorded, not changed)

- `settings get secure default_input_method`:
  `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` (Gboard, Latin).
- Enabled IMEs included the same Latin IME and SwiftKey; no Vietnamese/Telex IME was active or selected.
  The IME configuration was not modified, so nothing needed restoring.
- The soft keyboard was shown over the form after text entry. In the prior attempt (`S9.2-a1`) the
  "Validate" tap at the button's screen position landed on the IME, which appended masked characters
  and produced no validation status. That is why the earlier single check was unclassified. Here the
  keyboard was hidden (`mInputShown=false` confirmed by `dumpsys input_method`) before the button tap.

## Steps and visible results

1. Tapped the built-in preset `Gemini API · Gemini 3.5 Flash Lite`. The form then showed endpoint
   `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, model
   `gemini-3.5-flash-lite`, and an auto-filled profile name.
2. Entered the credential literally into the masked field from the environment without printing or
   logging it. The masked field length was read back as exactly 53 characters, equal to the
   source credential length, so no characters were dropped or duplicated by input.
3. Tapped **Validate connection and save** once (keyboard hidden). Observed status text:
   `Connection verified. Provider settings saved on this device.`
   (`device-provider-validation.xml`). A saved profile row appeared.
4. Tapped **Use provider** and observed the profile row:
   `ACTIVE · Gemini API · Gemini 3.5 Flash Lite · gemini-3.5-flash-lite`
   (`device-provider-active.xml`).

The subsequent live runs used exactly this profile; the provider request URL recorded in the app trace is
`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent?alt=sse`
with `x-goog-api-key` in `redactedHeaders` and the key value never present in any artifact, UI dump or log.

No credential value appears in this evidence directory.
