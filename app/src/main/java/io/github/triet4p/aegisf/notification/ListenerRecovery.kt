package io.github.triet4p.aegisf.notification

import android.service.notification.StatusBarNotification

/**
 * Reconciles the notifications that are still active on the system side after a (re)connect,
 * through the same privacy boundary a live callback passes.
 *
 * Each supplied post is judged exactly like a fresh callback delivery: the allowlist
 * ([isIntakePermitted]) is checked from routing metadata first, and only then is the
 * supported content screened ([decideNotificationIntake]) — OTP/security messages, group
 * summaries, payloads with no text, and redaction-only payloads are discarded with no body
 * retained. A permitted candidate is returned with its platform provenance (package, key,
 * post time) so the caller can store it through the same persisted delivery/evidence
 * identity a callback uses; an identical active post therefore writes nothing, a later
 * equal-evidence post becomes its own flagged candidate, and a confirmed or dismissed
 * draft is never reopened.
 *
 * Only the posts given to this function are considered: anything the system already
 * removed is not claimed, reconstructed, or treated as history. The caller passes whatever
 * the system reports as currently active — typically
 * `NotificationListenerService.getActiveNotifications()` — and nothing else. Revocation is
 * fail-closed: when [accessGranted] is false or the allowlist snapshot is not the loaded
 * ready one, every candidate is refused without a single content field being read.
 *
 * This function reads the supplied posts and parses their content only; it performs no
 * disk I/O and launches nothing. The caller runs it on its background dispatcher and
 * performs the actual stores behind its own connected-state guard, so a revocation that
 * lands mid-reconciliation still writes nothing afterwards.
 */
fun reconcileActivePosts(
    active: List<StatusBarNotification>,
    accessGranted: Boolean,
    allowedPackages: Set<String>,
): List<ActiveReconciledCandidate> {
    if (!accessGranted) {
        return emptyList()
    }
    return active.mapNotNull { posted ->
        if (
            !isIntakePermitted(
                accessGranted = true,
                allowedPackages = allowedPackages,
                packageName = posted.packageName,
            )
        ) {
            null
        } else {
            when (val decision = decideNotificationIntake(posted, allowedPackages)) {
                is NotificationIntakeDecision.Permitted ->
                    ActiveReconciledCandidate(
                        sourcePackage = posted.packageName,
                        notificationKey = posted.key,
                        postedAtEpochMillis = posted.postTime,
                        content = decision.content,
                    )
                else -> null
            }
        }
    }
}

/**
 * One still-active notification that survived the same privacy boundary a callback passes.
 *
 * The content is the supported permitted fields only — never a retained rejected body —
 * and the provenance beside it names the exact delivered post the persisted
 * delivery/evidence identity is computed from.
 */
data class ActiveReconciledCandidate(
    val sourcePackage: String,
    val notificationKey: String?,
    val postedAtEpochMillis: Long,
    val content: PermittedNotificationContent,
)
