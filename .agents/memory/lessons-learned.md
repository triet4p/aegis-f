# Lessons learned

## [2026-10-17] Android NotificationListenerService drops binding after update or background kill without auto-rebind

**Symptom:** Recent bank notifications were not captured into pending drafts; drafts only appeared after manual app interactions or not at all.
**Root cause:** Android's NotificationManagerService unbinds NotificationListenerService upon package replacement (`adb install -r`) or aggressive OEM background process kills. Without automatic rebind and active reconciliation on app/screen resume, background notifications were missed and not reconciled into drafts before the user swiped them away from the status bar.
**Fix / workaround:** Added `requestActiveReconciliation` hooks on `MainActivity.onResume`, `AegisApplication.onCreate`, and `BankDraftReviewScreen` entry. If disconnected, automatic rebind with `setComponentEnabledSetting` toggle fallback is invoked; if connected, off-thread `reconcileNow` processes any active status-bar notifications immediately.
**Watch out for:** Relying on `NotificationListenerService` callbacks without active notification reconciliation on user-visible screen entry, especially on custom OEM Android skins (Honor MagicOS / EMUI / MIUI) that restrict background execution.
