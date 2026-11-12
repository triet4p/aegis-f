# S10.8 capture index (round 2)

Instrumented native captures (`s108-*`, production screens via `AegisTheme`, isolated package):

| File | Screen/state |
| --- | --- |
| `s108-01-categories-en.png` | Categories list, normal, EN |
| `s108-02-archive-confirm-en.png` | Archive confirmation |
| `s108-03-categories-archived-en.png` | Archived row: badge `Archived` + caption `History kept` |
| `s108-04-delete-impact-en.png` | Referenced delete blocked; counts + Archive instead, no destructive button |
| `s108-04b-delete-unreferenced-en.png` | Unreferenced delete confirmation |
| `s108-04c-delete-race-blocked-en.png` | Reference added after impact read; transaction refuses and dialog switches to blocked |
| `s108-05-rules-en.png` | Rules tab with stored exact rule |
| `s108-06-settings-hub-en.png` | Settings navigation hub, EN |
| `s108-07-bank-settings-en.png` | Bank status/diagnostics |
| `s108-07b-bank-allowlist-en.png` | Bank allowlist/refresh area |
| `s108-08-provider-saved-en.png` | Provider verified/saved status |
| `s108-08b-provider-profile-en.png` | Saved profile card + profile controls |
| `s108-09-provider-offline-en.png` | Throwing-transport failure, edits kept |
| `s108-10-skills-history-en.png` | Skills & stored history |
| `s108-11-privacy-en.png` | Privacy & retention disclosure |
| `s108-12-categories-vi-large.png` | VI 1.5× categories/filters/rows/IME |
| `s108-13-provider-vi-ime.png` | VI 1.5× provider with real IME, sticky CTA |
| `s108-14-provider-vi-large.png` | VI 1.5× protocol sheet |
| `s108-15-provider-no-network-en.png` | Production no-network signal; zero calls/profiles |
| `s108-16-read-error-en.png` | Forced closed-storage read error; Add disabled |
| `s108-17-rule-stale-category-en.png` | Category archived while rule sheet open; refusal + stale choice dropped |

Real-shell captures (`device-*`, isolated package, production UI):

| File | Observation |
| --- | --- |
| `device-categories-empty-dark.png` | Empty categories |
| `device-home-45000-final-dark.png` | Home 45,000 ₫ after archive/blocked delete |
| `device-delete-blocked-final-dark.png` | Referenced Gym delete blocked; 1 transaction; Archive instead; no destructive action |
| `device-archived-row-final-dark.png` | Archived row split wording |
| `device-delete-archived-blocked-final-dark.png` | Archived referenced category blocked; only Cancel |
| `device-delete-unreferenced-final-dark.png` | Unreferenced Gym2 destructive confirmation |
| `device-ledger-final-dark.png` | Record -45,000 ₫ · Gym (archived) · Manual |
| `device-rule-saved-final-dark.png` | Exact rule `gym → Gym` saved |
| `device-rule-remove-confirm-dark.png` | Rule remove confirmation |
| `device-rule-removed-dark.png` | Rule removed |
| `device-settings-dark.png` | Settings hub dark |
| `device-settings-light.png` | Settings hub light |
| `device-bank-light.png` | Bank child light |
| `device-provider-final-dark.png` | Provider child final dark, empty profiles |
| `device-skills-history-dark.png` | Skills/history child dark |
| `device-privacy-dark.png` | Privacy child dark |
| `device-system-notification-access.png` | System notification-access page opened from Bank and returned; no change |
| `device-system-app-info.png` | System app-info page opened from Bank and returned; no change |
