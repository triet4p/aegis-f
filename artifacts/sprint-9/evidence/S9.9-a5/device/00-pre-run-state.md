# S9.9-a5 pre-run state (recorded before the first hosted workflow attempt)

Device: ALT-LX2
serial: AAAJBB4930914141
device date: 2026-11-07 14:42 +07 (Asia/Ho_Chi_Minh)
IME: com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
screen_off_timeout: 2147483647 (no mid-run display timeout; mWakefulness=Awake)

## Source snapshot (unchanged since a4)
manifest paths: 32; mismatches: 0 []
manifest sha256 (a4 file, byte-identical): 7d0cf80bcf3c918591db072c7eb0abbff608f95234f1f84599ec70a0503fa293
worker digest: 7d0cf80bcf3c918591db072c7eb0abbff608f95234f1f84599ec70a0503fa293
Note: `sha256sum -c` in this shell reported 21 false mismatches (its manifest/stdin handling); the authoritative
binary recomputation above matches 32/32 and direct `sha256sum <path>` equals each manifest entry.

## Installed APK correspondence (device == local build output)
app   3e8e4b6a3fdf158db3edd7aaa2efdb083e2428e9749415ef81211b589df55c8e (local == device)
test  80c75cd6c969d1da5f08496692ce84d30a0598fa5c2265eec305b1816c057db6 (local == device)
Test APK mtime 2026-11-06 17:17:47 newer than every androidTest source (latest 17:16:31);
app APK mtime 2026-11-06 16:51 newer than every main source (latest 16:27).
Unit 594/0/0/3 and lint 0 errors/18 warnings/2 hints already proven on this source; not rerun.

## Provider store before staging
shared_prefs/hosted_provider_settings.xml = 65 bytes `<map />`, device sha256
3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793 (pre-run empty preservation;
the original three isolated profiles remain lost and are NOT restored).

## Key transfer (private stdin, value never printed)
.env DEEPSEEK_API_KEY length 35, LF line (CRLF only on the two other lines).
`awk ... | adb shell "run-as <pkg> sh -c 'cat > files/s98_secret.env'"` -> 53 bytes,
sha256 aa489fb99a7d67a5786e8152ecfd8aa72467db7f6b162d444cccea932731448a on both host line and device file.
Staging stage deleted the file (secretFileDeleted=true).

## Profile staging (S98DeviceFixtureInstrumentedTest#stage add_custom_profile + activate_profile)
S98CONFIG custom id=s99-direct-deepseek-flash model=deepseek-flash
  endpoint=https://api.deepseek.com/chat/completions protocol=OPENAI_CHAT_COMPLETIONS
  credentialNonBlank=true credentialMatch=true
S98CONFIG activated=s99-direct-deepseek-flash active=s99-direct-deepseek-flash
prefs after activation: 866 bytes; raw key present in file: False (encrypted_profiles + encryption_key_alias)

## Fail-closed model proof (no network)
S99M guard profile id=s99-direct-deepseek-flash model=deepseek-flash protocol=OPENAI_CHAT_COMPLETIONS endpoint=https://api.deepseek.com/chat/completions maxOutputTokens=null reasoning=null
S99M guard serializedModel=deepseek-flash wireMatches=true limitFields=[] maxOutputTokens=null
S99M guard installed=true expectedModel=deepseek-flash streamingCapable=true
S99M modelProof OK guardChecks=0

## Pre-run fixture audit (read-only, production query service)
pending=10; no pending draft carries 89000 or 54000; categories=4:Food,1:Fuel,2:Fuel,3:Transport;
activeCaptures=49; drafts 60-63 DISMISSED (a4 fixtures); ledger day 2026-11-07 expected 0/0
(no ledger writes after a4's cleanup; the measurement prints the live baseline first).
