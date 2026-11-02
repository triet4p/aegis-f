# Main notification permission verification

Owner accepts that old development data need not be restored; latest request is to check permissions and preserve prior access. No DB restore performed.

Observed with adb secure settings and dumpsys package/activity services: notification access includes BankNotificationListener; system service binder hasBound=true; INTERNET granted; app installed, enabled, not stopped. Package requests no POST_NOTIFICATIONS, SMS or accessibility runtime permissions. deviceidle whitelist includes main package. Vendor-specific launch controls were not individually inspected; no claim of unchanged historical vendor settings.

Bank allowlist file was absent after uninstall. Restored only previously owner-selected com.vietinbank.ipay and com.VCB through actual Settings > Bank notifications > Add bank app UI. Verified SharedPreferences contains exactly both; both rows show Ready. Final actual UI shows Notification access Granted, Listener connected, Bank apps listed 2 packages. No synthetic bank post, no new-bank live delivery proof, no financial record creation.

No builds/installs/uninstalls/test runner used in this check. CAT1 independent review/checkpoint remain incomplete; no PASS or commit implied. All prior review/incident outcomes remain preserved.
