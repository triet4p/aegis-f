package io.github.triet4p.aegisf.ui.chat

import io.github.triet4p.aegisf.agent.WorkflowRunStopReason
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeReviewStatus

/**
 * The vocabulary of the settled Chat outcome: verified effect cards, link decisions, run state
 * and the bounded follow-up row.
 *
 * It is separate from [ChatStrings] because those strings describe the conversation shell rather
 * than the cards a settled run leaves behind, and because the cards are also rendered by other
 * screens that own their own shell copy. English is the baseline; Vietnamese is a 1:1
 * translation of the same copy and every parameterized sentence is built here so a translation
 * cannot silently fall back to English.
 */
internal data class ChatOutcomeStrings(
    val language: AegisLanguage,
    val noneVerified: String,
    val run: ChatRunStrings,
    val incomplete: ChatIncompleteStrings,
    val noEffect: ChatNoEffectStrings,
    val event: ChatEventStrings,
    val link: ChatLinkStrings,
    val category: ChatCategoryStrings,
    val replay: ChatReplayStrings,
    val followUp: ChatFollowUpStrings,
) {
    fun eventTitle(eventId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Event #$eventId"
        AegisLanguage.VIETNAMESE -> "Sự kiện #$eventId"
    }
}

internal data class ChatRunStrings(
    val language: AegisLanguage,
    val notStored: String,
    val unreadable: String,
    val succeeded: String,
    val cancelled: String,
    val clarifying: String,
    val failed: String,
    val interrupted: String,
    val other: String,
) {
    fun storedState(state: String?): String = when (state) {
        "SUCCEEDED" -> succeeded
        "CANCELLED" -> cancelled
        "CLARIFYING" -> clarifying
        "FAILED" -> failed
        "RUNNING", "PENDING" -> interrupted
        else -> other
    }
}

internal data class ChatIncompleteStrings(
    val language: AegisLanguage,
    val retryLast: String,
    val explanation: String,
) {
    fun message(reason: WorkflowRunStopReason): String {
        val reasonText = reason(reason)
        return when (language) {
            AegisLanguage.ENGLISH ->
                "Run incomplete: $reasonText. You can retry or continue manually."
            AegisLanguage.VIETNAMESE ->
                "Lượt chạy chưa hoàn tất: $reasonText. Bạn có thể thử lại hoặc tiếp tục thủ công."
        }
    }

    private fun reason(reason: WorkflowRunStopReason): String = when (reason) {
        WorkflowRunStopReason.TURN_BUDGET -> when (language) {
            AegisLanguage.ENGLISH -> "the turn limit was reached"
            AegisLanguage.VIETNAMESE -> "đã chạm giới hạn số lượt"
        }

        WorkflowRunStopReason.TOOL_CALL_BUDGET -> when (language) {
            AegisLanguage.ENGLISH -> "the tool-call limit was reached"
            AegisLanguage.VIETNAMESE -> "đã chạm giới hạn số lần gọi công cụ"
        }

        WorkflowRunStopReason.CONTEXT_BUDGET -> when (language) {
            AegisLanguage.ENGLISH -> "the context limit was reached"
            AegisLanguage.VIETNAMESE -> "đã chạm giới hạn ngữ cảnh"
        }

        WorkflowRunStopReason.TIME_BUDGET -> when (language) {
            AegisLanguage.ENGLISH -> "the time limit was reached"
            AegisLanguage.VIETNAMESE -> "đã chạm giới hạn thời gian"
        }

        WorkflowRunStopReason.LOOP_DETECTED -> when (language) {
            AegisLanguage.ENGLISH -> "a repeated call crossed its bound"
            AegisLanguage.VIETNAMESE -> "một lệnh lặp đã vượt giới hạn"
        }

        WorkflowRunStopReason.TRUNCATED_TURN -> when (language) {
            AegisLanguage.ENGLISH -> "the provider response was truncated"
            AegisLanguage.VIETNAMESE -> "phản hồi của nhà cung cấp bị cắt ngắn"
        }

        WorkflowRunStopReason.CONTENT_FILTERED -> when (language) {
            AegisLanguage.ENGLISH -> "the provider filtered the content"
            AegisLanguage.VIETNAMESE -> "nhà cung cấp đã lọc nội dung"
        }

        WorkflowRunStopReason.PROVIDER_FAILURE -> when (language) {
            AegisLanguage.ENGLISH -> "the provider could not complete the request"
            AegisLanguage.VIETNAMESE -> "nhà cung cấp không hoàn tất được yêu cầu"
        }

        WorkflowRunStopReason.MASKING_BLOCKED -> when (language) {
            AegisLanguage.ENGLISH -> "privacy protection stopped the request"
            AegisLanguage.VIETNAMESE -> "bảo vệ quyền riêng tư đã chặn yêu cầu"
        }

        WorkflowRunStopReason.MALFORMED_TURN -> when (language) {
            AegisLanguage.ENGLISH -> "the provider reply had an unusable shape"
            AegisLanguage.VIETNAMESE -> "phản hồi của nhà cung cấp có cấu trúc không dùng được"
        }

        WorkflowRunStopReason.TOOL_FAILURE -> when (language) {
            AegisLanguage.ENGLISH -> "an application action failed safely"
            AegisLanguage.VIETNAMESE -> "một thao tác ứng dụng đã thất bại an toàn"
        }

        WorkflowRunStopReason.EFFECT_NOT_VERIFIED -> when (language) {
            AegisLanguage.ENGLISH -> "the stated change could not be verified"
            AegisLanguage.VIETNAMESE -> "không xác minh được thay đổi đã nêu"
        }

        WorkflowRunStopReason.REQUIRED_EFFECT_MISSING -> when (language) {
            AegisLanguage.ENGLISH -> "a required change was not recorded"
            AegisLanguage.VIETNAMESE -> "một thay đổi bắt buộc chưa được ghi"
        }

        WorkflowRunStopReason.EMPTY_ANSWER -> when (language) {
            AegisLanguage.ENGLISH -> "the provider returned no answer"
            AegisLanguage.VIETNAMESE -> "nhà cung cấp không trả về câu trả lời"
        }
    }
}

internal data class ChatNoEffectStrings(
    val emptySearch: String,
    val policyRefusal: String,
) {
    fun linkNoMatch(eventId: Long, candidatesExamined: Int, language: AegisLanguage): String =
        when (language) {
            AegisLanguage.ENGLISH -> "Event #$eventId stayed unlinked. " +
                "$candidatesExamined candidate(s) examined; none was attachable. " +
                "Choose one explicitly or record the event manually."
            AegisLanguage.VIETNAMESE -> "Sự kiện #$eventId vẫn chưa được liên kết. " +
                "Đã xem $candidatesExamined ứng viên; không ứng viên nào có thể đính kèm. " +
                "Hãy chọn thủ công hoặc ghi sự kiện bằng tay."
        }

    fun alreadyInterpreted(
        eventId: Long,
        revision: Int,
        language: AegisLanguage,
    ): String = when (language) {
        AegisLanguage.ENGLISH -> "Event #$eventId already carries that verified reading " +
            "(revision $revision). Nothing was rewritten."
        AegisLanguage.VIETNAMESE -> "Sự kiện #$eventId đã có kết quả đọc đã xác minh đó " +
            "(bản sửa $revision). Không có gì bị ghi lại."
    }
}

internal data class ChatEventStrings(
    val language: AegisLanguage,
    val loadError: String,
    val reload: String,
    val missing: String,
    val needsReview: String,
    val editInterpretation: String,
    val reviewSource: String,
    val browseDrafts: String,
    val recordManually: String,
) {
    fun interpreted(revision: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "Interpretation ready · revision $revision"
        AegisLanguage.VIETNAMESE -> "Kết quả đọc đã sẵn sàng · bản sửa $revision"
    }

    fun unresolved(reason: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Unresolved · $reason"
        AegisLanguage.VIETNAMESE -> "Chưa rõ · $reason"
    }

    fun noMatch(): String = when (language) {
        AegisLanguage.ENGLISH -> "Unmatched · no active bank draft"
        AegisLanguage.VIETNAMESE -> "Chưa khớp · không có bản nháp ngân hàng đang hoạt động"
    }

    fun changedSinceRun(reported: Int, current: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "Changed since this run: the run stored revision $reported, " +
            "the current revision is $current. Actions below use the current revision."
        AegisLanguage.VIETNAMESE -> "Đã thay đổi sau lượt chạy này: lượt chạy lưu bản sửa " +
            "$reported, bản sửa hiện tại là $current. Các thao tác bên dưới dùng bản sửa hiện tại."
    }

    fun sourceConflict(sourceRevision: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "Source conflict: this reading came from capture revision " +
            "$sourceRevision. Re-read the current source before linking or recording."
        AegisLanguage.VIETNAMESE -> "Xung đột nguồn: kết quả đọc này đến từ bản sửa ghi chú " +
            "$sourceRevision. Hãy đọc lại nguồn hiện tại trước khi liên kết hoặc ghi."
    }

    fun excerpt(text: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Selected source excerpt: \u201c$text\u201d"
        AegisLanguage.VIETNAMESE -> "Đoạn nguồn đã chọn: \u201c$text\u201d"
    }

    fun reading(text: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Reading: $text"
        AegisLanguage.VIETNAMESE -> "Kết quả đọc: $text"
    }

    fun amount(formatted: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Interpretation amount: $formatted"
        AegisLanguage.VIETNAMESE -> "Số tiền đã đọc: $formatted"
    }

    fun merchant(name: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Merchant: $name"
        AegisLanguage.VIETNAMESE -> "Đơn vị: $name"
    }

    fun recordedManually(transactionId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Recorded manually as ledger record #$transactionId."
        AegisLanguage.VIETNAMESE -> "Đã ghi thủ công thành bản ghi sổ #$transactionId."
    }

    fun statedDateTime(anchor: String, end: String?, zone: String?): String {
        val unspecified = when (language) {
            AegisLanguage.ENGLISH -> "unspecified"
            AegisLanguage.VIETNAMESE -> "chưa xác định"
        }
        return buildString {
            append(
                when (language) {
                    AegisLanguage.ENGLISH -> "Stated date/time: "
                    AegisLanguage.VIETNAMESE -> "Ngày/giờ đã nêu: "
                },
            )
            append(anchor.ifBlank { unspecified })
            if (!end.isNullOrBlank()) {
                append(
                    when (language) {
                        AegisLanguage.ENGLISH -> " to $end"
                        AegisLanguage.VIETNAMESE -> " đến $end"
                    },
                )
            }
            zone?.let { append(" · $it") }
        }
    }
}

internal data class ChatLinkStrings(
    val language: AegisLanguage,
    val reload: String,
    val loadError: String,
    val saving: String,
    val attachAction: String,
    val rejectAction: String,
    val attachFailed: String,
    val rejectFailed: String,
    val unlinkFailed: String,
    val rejectionFinal: String,
) {
    fun proposed(eventId: Long, draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Proposed · event #$eventId to bank draft #$draftId (not attached)"
        AegisLanguage.VIETNAMESE -> "Đề xuất · sự kiện #$eventId với bản nháp ngân hàng #$draftId (chưa đính kèm)"
    }

    fun attached(eventId: Long, draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Attached · event #$eventId to bank draft #$draftId"
        AegisLanguage.VIETNAMESE -> "Đã đính kèm · sự kiện #$eventId với bản nháp ngân hàng #$draftId"
    }

    fun rejected(eventId: Long, draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH ->
            "Rejected · event #$eventId to bank draft #$draftId (decision retained)"
        AegisLanguage.VIETNAMESE ->
            "Đã từ chối · sự kiện #$eventId với bản nháp ngân hàng #$draftId (quyết định được giữ)"
    }

    fun loading(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Loading bank draft #$draftId…"
        AegisLanguage.VIETNAMESE -> "Đang tải bản nháp ngân hàng #$draftId…"
    }

    fun draftLoadFailed(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Bank draft #$draftId could not be loaded."
        AegisLanguage.VIETNAMESE -> "Không tải được bản nháp ngân hàng #$draftId."
    }

    fun changedSinceRun(reported: Int, current: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "Changed since this run: the run reported revision $reported, " +
            "the current revision is $current. Actions below use the current revision."
        AegisLanguage.VIETNAMESE -> "Đã thay đổi sau lượt chạy này: lượt chạy báo bản sửa " +
            "$reported, bản sửa hiện tại là $current. Các thao tác bên dưới dùng bản sửa hiện tại."
    }

    fun noConflictingEvidence(): String = when (language) {
        AegisLanguage.ENGLISH -> "No conflicting stored evidence was recorded. " +
            "Attaching never confirms the draft or changes the ledger."
        AegisLanguage.VIETNAMESE -> "Không có bằng chứng xung đột nào được lưu. " +
            "Đính kèm không xác nhận bản nháp hay thay đổi sổ."
    }

    fun matchedByStoredEvidence(): String = when (language) {
        AegisLanguage.ENGLISH -> "Matched by this draft's stored amount and moment. " +
            "Attaching never confirms the draft or changes the ledger."
        AegisLanguage.VIETNAMESE -> "Khớp theo số tiền và thời điểm đã lưu của bản nháp. " +
            "Đính kèm không xác nhận bản nháp hay thay đổi sổ."
    }

    /**
     * A stored decision note, or null when it is the row's own chat action sentence.
     *
     * Attach/Reject already state their outcome on this card, so repeating the recorded action
     * sentence would show the same fact twice. Any other user-supplied note stays visible.
     */
    fun decisionNote(note: String): String? = when (note.trim()) {
        CHAT_ATTACH_NOTE, CHAT_REJECT_NOTE -> null
        else -> when (language) {
            AegisLanguage.ENGLISH -> "Decision note: $note"
            AegisLanguage.VIETNAMESE -> "Ghi chú quyết định: $note"
        }
    }

    private companion object {
        const val CHAT_ATTACH_NOTE = "User explicitly attached this draft in Chat."
        const val CHAT_REJECT_NOTE = "User explicitly rejected this draft in Chat."
    }

    fun attachedMessage(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH ->
            "Draft #$draftId attached. The bank amount and ledger are unchanged."
        AegisLanguage.VIETNAMESE ->
            "Đã đính kèm bản nháp #$draftId. Số tiền ngân hàng và sổ không thay đổi."
    }

    fun rejectedMessage(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Draft #$draftId rejected. That decision is retained."
        AegisLanguage.VIETNAMESE -> "Đã từ chối bản nháp #$draftId. Quyết định đó được giữ nguyên."
    }

    fun unlinkedMessage(): String = when (language) {
        AegisLanguage.ENGLISH ->
            "Draft unlinked. The event can be attached to another draft or recorded manually."
        AegisLanguage.VIETNAMESE ->
            "Đã bỏ liên kết. Sự kiện có thể được đính kèm với bản nháp khác hoặc ghi thủ công."
    }

    fun unlinkAction(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Unlink draft #$draftId"
        AegisLanguage.VIETNAMESE -> "Bỏ liên kết bản nháp #$draftId"
    }

    fun confirmedLedgerRecord(): String = when (language) {
        AegisLanguage.ENGLISH -> "This draft is a confirmed ledger record. The note reference is " +
            "kept as provenance and the ledger is unchanged."
        AegisLanguage.VIETNAMESE -> "Bản nháp này đã là bản ghi sổ đã xác nhận. Tham chiếu ghi chú " +
            "được giữ làm nguồn gốc và sổ không thay đổi."
    }

    fun failure(outcome: io.github.triet4p.aegisf.data.WorkflowCommandOutcome): String = when (outcome) {
        is io.github.triet4p.aegisf.data.WorkflowCommandOutcome.Conflict -> when (language) {
            AegisLanguage.ENGLISH ->
                "Conflict: ${outcome.reason.trimEnd('.')}. The decision was not changed; " +
                    "reload and try again."
            AegisLanguage.VIETNAMESE ->
                "Xung đột: ${outcome.reason.trimEnd('.')}. Quyết định chưa bị thay đổi; " +
                    "hãy tải lại và thử lại."
        }

        is io.github.triet4p.aegisf.data.WorkflowCommandOutcome.Denied -> denied(outcome.reason)
        is io.github.triet4p.aegisf.data.WorkflowCommandOutcome.Failed -> outcome.reason
        io.github.triet4p.aegisf.data.WorkflowCommandOutcome.NotFound -> when (language) {
            AegisLanguage.ENGLISH -> "The event or draft is no longer available."
            AegisLanguage.VIETNAMESE -> "Sự kiện hoặc bản nháp không còn khả dụng."
        }

        io.github.triet4p.aegisf.data.WorkflowCommandOutcome.Cancelled -> when (language) {
            AegisLanguage.ENGLISH -> "The link decision was cancelled."
            AegisLanguage.VIETNAMESE -> "Quyết định liên kết đã bị huỷ."
        }

        is io.github.triet4p.aegisf.data.WorkflowCommandOutcome.NeedsClarification -> outcome.reason
        is io.github.triet4p.aegisf.data.WorkflowCommandOutcome.Applied,
        is io.github.triet4p.aegisf.data.WorkflowCommandOutcome.NoChange,
        -> when (language) {
            AegisLanguage.ENGLISH -> "The link decision did not complete. Reload and try again."
            AegisLanguage.VIETNAMESE -> "Quyết định liên kết chưa hoàn tất. Hãy tải lại và thử lại."
        }
    }

    private fun denied(
        reason: io.github.triet4p.aegisf.data.WorkflowDenialReason,
    ): String = when (reason) {
        io.github.triet4p.aegisf.data.WorkflowDenialReason.LINK_ALREADY_REJECTED -> when (language) {
            AegisLanguage.ENGLISH ->
                "This event/draft pair was already rejected; that decision cannot be undone."
            AegisLanguage.VIETNAMESE ->
                "Cặp sự kiện/bản nháp này đã bị từ chối; quyết định đó không thể hoàn tác."
        }

        io.github.triet4p.aegisf.data.WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS -> when (language) {
            AegisLanguage.ENGLISH ->
                "Unlink the currently active draft before selecting another."
            AegisLanguage.VIETNAMESE ->
                "Hãy bỏ liên kết bản nháp đang hoạt động trước khi chọn bản nháp khác."
        }

        io.github.triet4p.aegisf.data.WorkflowDenialReason.EVENT_ALREADY_RECORDED -> when (language) {
            AegisLanguage.ENGLISH ->
                "This event is already recorded manually and cannot be linked again."
            AegisLanguage.VIETNAMESE ->
                "Sự kiện này đã được ghi thủ công và không thể liên kết lại."
        }

        io.github.triet4p.aegisf.data.WorkflowDenialReason.EVENT_UNAVAILABLE -> when (language) {
            AegisLanguage.ENGLISH ->
                "This interpretation is unavailable. Re-read the current source before linking."
            AegisLanguage.VIETNAMESE ->
                "Kết quả đọc này không còn khả dụng. Hãy đọc lại nguồn hiện tại trước khi liên kết."
        }

        io.github.triet4p.aegisf.data.WorkflowDenialReason.CONFIRMED_RECORD -> when (language) {
            AegisLanguage.ENGLISH ->
                "This draft is already a confirmed ledger record, so its note reference is kept."
            AegisLanguage.VIETNAMESE ->
                "Bản nháp này đã là bản ghi sổ đã xác nhận nên tham chiếu ghi chú được giữ lại."
        }

        else -> when (language) {
            AegisLanguage.ENGLISH ->
                "The link decision was refused: ${reason.name.lowercase().replace('_', ' ')}."
            AegisLanguage.VIETNAMESE ->
                "Quyết định liên kết bị từ chối: ${reason.name.lowercase().replace('_', ' ')}."
        }
    }

    fun state(state: ContextLinkState, eventId: Long, draftId: Long): String = when (state) {
        ContextLinkState.PROPOSED -> proposed(eventId, draftId)
        ContextLinkState.ACCEPTED -> attached(eventId, draftId)
        ContextLinkState.REJECTED -> rejected(eventId, draftId)
    }

    fun reviewStatus(status: IntakeReviewStatus?): String = when (status) {
        null -> when (language) {
            AegisLanguage.ENGLISH -> "unavailable"
            AegisLanguage.VIETNAMESE -> "không khả dụng"
        }

        IntakeReviewStatus.PENDING -> when (language) {
            AegisLanguage.ENGLISH -> "pending"
            AegisLanguage.VIETNAMESE -> "đang chờ"
        }

        IntakeReviewStatus.CONFIRMED -> when (language) {
            AegisLanguage.ENGLISH -> "confirmed"
            AegisLanguage.VIETNAMESE -> "đã xác nhận"
        }

        IntakeReviewStatus.DISMISSED -> when (language) {
            AegisLanguage.ENGLISH -> "dismissed"
            AegisLanguage.VIETNAMESE -> "đã bỏ qua"
        }
    }

    fun draftLine(
        status: IntakeReviewStatus?,
        draftId: Long,
        amount: String?,
        memo: String?,
    ): String = buildString {
        append(reviewStatus(status))
        append(
            when (language) {
                AegisLanguage.ENGLISH -> " · bank draft #$draftId"
                AegisLanguage.VIETNAMESE -> " · bản nháp ngân hàng #$draftId"
            },
        )
        amount?.let { append(" · $it") }
        memo?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
    }
}

internal data class ChatCategoryStrings(
    val language: AegisLanguage,
    val changeInReview: String,
) {
    fun saved(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Category saved on draft #$draftId"
        AegisLanguage.VIETNAMESE -> "Đã lưu danh mục cho bản nháp #$draftId"
    }

    fun chosen(name: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Chosen category: $name (review state only)."
        AegisLanguage.VIETNAMESE -> "Danh mục đã chọn: $name (chỉ là trạng thái duyệt)."
    }

    fun unavailable(categoryId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Chosen category #$categoryId is no longer in the catalog; " +
            "the stored choice was refused or superseded."
        AegisLanguage.VIETNAMESE -> "Danh mục đã chọn #$categoryId không còn trong danh mục; " +
            "lựa chọn đã lưu bị từ chối hoặc bị thay thế."
    }

    fun reviewStatus(status: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Draft review status: $status · " +
            "the draft stays pending and no ledger record was written."
        AegisLanguage.VIETNAMESE -> "Trạng thái duyệt bản nháp: $status · " +
            "bản nháp vẫn đang chờ và chưa có bản ghi sổ nào được ghi."
    }
}

internal data class ChatReplayStrings(
    val language: AegisLanguage,
    val title: String,
    val missing: String,
    val loadError: String,
    val loading: String,
    val stillMatches: String,
    val superseded: String,
) {
    fun journal(command: String, outcome: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Journal $command · outcome $outcome."
        AegisLanguage.VIETNAMESE -> "Nhật ký $command · kết quả $outcome."
    }
}

internal data class ChatFollowUpStrings(
    val language: AegisLanguage,
    val heading: String,
    val askLabel: String,
    val ambiguous: String,
    val unavailable: String,
    val alreadyUsed: String,
    val otherRun: String,
    val failed: String,
    val sending: String,
    val send: String,
) {
    fun eventRef(id: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Event #$id"
        AegisLanguage.VIETNAMESE -> "Sự kiện #$id"
    }

    fun draftRef(id: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Draft #$id"
        AegisLanguage.VIETNAMESE -> "Bản nháp #$id"
    }

    fun linkRef(id: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Link #$id"
        AegisLanguage.VIETNAMESE -> "Liên kết #$id"
    }

    fun about(label: String, userInput: String): String = "$userInput ($label)"
}

internal fun chatOutcomeStrings(language: AegisLanguage): ChatOutcomeStrings =
    when (language) {
        AegisLanguage.ENGLISH -> englishChatOutcomeStrings
        AegisLanguage.VIETNAMESE -> vietnameseChatOutcomeStrings
    }

private val englishChatOutcomeStrings = ChatOutcomeStrings(
    language = AegisLanguage.ENGLISH,
    noneVerified = "Verified application changes: none.",
    run = ChatRunStrings(
        language = AegisLanguage.ENGLISH,
        notStored = "This exchange is not stored as a run yet.",
        unreadable = "The stored run state could not be re-read.",
        succeeded = "Saved to this device.",
        cancelled = "Stopped; stored effects stay in place.",
        clarifying = "Waiting for your answer.",
        failed = "This attempt ended without a verified result.",
        interrupted = "This attempt was interrupted; it did not resume.",
        other = "The stored run state is not a completed exchange.",
    ),
    incomplete = ChatIncompleteStrings(
        language = AegisLanguage.ENGLISH,
        retryLast = "Retry last request",
        explanation = "Retry re-sends the same request through the same provider and scope; " +
            "stored rows are re-read first, so a repeated retry cannot duplicate an event, " +
            "link, or ledger record.",
    ),
    noEffect = ChatNoEffectStrings(
        emptySearch = "No stored draft matched the bounded search. Nothing was attached.",
        policyRefusal = "Request refused as outside the declared catalog. " +
            "No application change was made.",
    ),
    event = ChatEventStrings(
        language = AegisLanguage.ENGLISH,
        loadError = "The stored reading could not be loaded.",
        reload = "Reload event",
        missing = "This reading is no longer stored. A later correction replaced it; " +
            "the card shows the run receipt only.",
        needsReview = "needs review",
        editInterpretation = "Edit interpretation",
        reviewSource = "Review source",
        browseDrafts = "Browse drafts",
        recordManually = "Record manually",
    ),
    link = ChatLinkStrings(
        language = AegisLanguage.ENGLISH,
        reload = "Reload link",
        loadError = "The stored link could not be loaded.",
        saving = "Saving link decision…",
        attachAction = "Attach draft",
        rejectAction = "Reject",
        attachFailed = "Attach failed. No financial amount was changed; reload and try again.",
        rejectFailed = "Reject failed. No decision was changed; reload and try again.",
        unlinkFailed = "Unlink failed. The stored decision is unchanged; try again.",
        rejectionFinal = "This refusal cannot be undone. You may browse a different draft " +
            "from the event card.",
    ),
    category = ChatCategoryStrings(
        language = AegisLanguage.ENGLISH,
        changeInReview = "Change the category in Review notifications; category choice never " +
            "confirms a transaction.",
    ),
    replay = ChatReplayStrings(
        language = AegisLanguage.ENGLISH,
        title = "Replayed operation",
        missing = "No journal row is stored for this receipt.",
        loadError = "The journal receipt could not be loaded.",
        loading = "Loading journal receipt…",
        stillMatches = "The original effect still matches the stored state; nothing was rewritten.",
        superseded = "The original effect no longer matches: a later correction or deletion " +
            "superseded it. The receipt stays historical.",
    ),
    followUp = ChatFollowUpStrings(
        language = AegisLanguage.ENGLISH,
        heading = "Follow up within the visible scope",
        askLabel = "Ask about what you see above",
        ambiguous = "Which one do you mean? Choose one of the cards above, or name its " +
            "event, draft, or link number — nothing was changed.",
        unavailable = "This follow-up is no longer available. Start a new request with a " +
            "fresh prompt.",
        alreadyUsed = "This follow-up was already used. Start a new request with a fresh prompt.",
        otherRun = "The follow-up belongs to another run. Start a new request with a fresh prompt.",
        failed = "The follow-up could not be sent. The run stayed on its current attempt.",
        sending = "Sending…",
        send = "Send follow-up",
    ),
)

private val vietnameseChatOutcomeStrings = ChatOutcomeStrings(
    language = AegisLanguage.VIETNAMESE,
    noneVerified = "Không có thay đổi ứng dụng nào được xác minh.",
    run = ChatRunStrings(
        language = AegisLanguage.VIETNAMESE,
        notStored = "Lượt trao đổi này chưa được lưu thành một lượt chạy.",
        unreadable = "Không đọc lại được trạng thái lượt chạy đã lưu.",
        succeeded = "Đã lưu trên thiết bị này.",
        cancelled = "Đã dừng; các thay đổi đã lưu vẫn được giữ nguyên.",
        clarifying = "Đang chờ câu trả lời của bạn.",
        failed = "Lượt này kết thúc mà không có kết quả được xác minh.",
        interrupted = "Lượt này bị gián đoạn; nó không tự chạy tiếp.",
        other = "Trạng thái lượt chạy đã lưu không phải một lượt trao đổi hoàn tất.",
    ),
    incomplete = ChatIncompleteStrings(
        language = AegisLanguage.VIETNAMESE,
        retryLast = "Thử lại yêu cầu trước",
        explanation = "Thử lại sẽ gửi lại đúng yêu cầu đó qua cùng nhà cung cấp và phạm vi; " +
            "các dòng đã lưu được đọc lại trước, nên thử lại nhiều lần không thể tạo trùng " +
            "sự kiện, liên kết hay bản ghi sổ.",
    ),
    noEffect = ChatNoEffectStrings(
        emptySearch = "Không có bản nháp đã lưu nào khớp tìm kiếm có giới hạn. Không có gì được đính kèm.",
        policyRefusal = "Yêu cầu bị từ chối vì nằm ngoài danh mục đã khai báo. " +
            "Không có thay đổi ứng dụng nào được ghi.",
    ),
    event = ChatEventStrings(
        language = AegisLanguage.VIETNAMESE,
        loadError = "Không tải được kết quả đọc đã lưu.",
        reload = "Tải lại sự kiện",
        missing = "Kết quả đọc này không còn được lưu. Một chỉnh sửa sau đó đã thay thế nó; " +
            "thẻ chỉ hiển thị biên nhận của lượt chạy.",
        needsReview = "cần xem lại",
        editInterpretation = "Sửa kết quả đọc",
        reviewSource = "Xem lại nguồn",
        browseDrafts = "Duyệt bản nháp",
        recordManually = "Ghi thủ công",
    ),
    link = ChatLinkStrings(
        language = AegisLanguage.VIETNAMESE,
        reload = "Tải lại liên kết",
        loadError = "Không tải được liên kết đã lưu.",
        saving = "Đang lưu quyết định liên kết…",
        attachAction = "Đính kèm bản nháp",
        rejectAction = "Từ chối",
        attachFailed = "Đính kèm thất bại. Không có số tiền nào bị thay đổi; hãy tải lại và thử lại.",
        rejectFailed = "Từ chối thất bại. Không có quyết định nào bị thay đổi; hãy tải lại và thử lại.",
        unlinkFailed = "Bỏ liên kết thất bại. Quyết định đã lưu không thay đổi; hãy thử lại.",
        rejectionFinal = "Không thể hoàn tác quyết định từ chối này. Bạn có thể duyệt bản nháp " +
            "khác từ thẻ sự kiện.",
    ),
    category = ChatCategoryStrings(
        language = AegisLanguage.VIETNAMESE,
        changeInReview = "Đổi danh mục trong Duyệt tin; việc chọn danh mục không bao giờ " +
            "xác nhận một giao dịch.",
    ),
    replay = ChatReplayStrings(
        language = AegisLanguage.VIETNAMESE,
        title = "Thao tác được phát lại",
        missing = "Không có dòng nhật ký nào được lưu cho biên nhận này.",
        loadError = "Không tải được biên nhận nhật ký.",
        loading = "Đang tải biên nhận nhật ký…",
        stillMatches = "Hiệu ứng ban đầu vẫn khớp với trạng thái đã lưu; không có gì bị ghi lại.",
        superseded = "Hiệu ứng ban đầu không còn khớp: một chỉnh sửa hoặc xoá sau đó đã thay thế nó. " +
            "Biên nhận vẫn mang tính lịch sử.",
    ),
    followUp = ChatFollowUpStrings(
        language = AegisLanguage.VIETNAMESE,
        heading = "Hỏi tiếp trong phạm vi đang hiển thị",
        askLabel = "Hỏi về nội dung bạn thấy ở trên",
        ambiguous = "Bạn muốn nói đến mục nào? Hãy chọn một thẻ ở trên hoặc nêu số " +
            "sự kiện, bản nháp hay liên kết — không có gì bị thay đổi.",
        unavailable = "Không thể hỏi tiếp lượt này nữa. Hãy bắt đầu yêu cầu mới với nội dung mới.",
        alreadyUsed = "Lượt hỏi tiếp này đã được dùng. Hãy bắt đầu yêu cầu mới với nội dung mới.",
        otherRun = "Lượt hỏi tiếp thuộc về một lượt chạy khác. Hãy bắt đầu yêu cầu mới với nội dung mới.",
        failed = "Không gửi được lượt hỏi tiếp. Lượt chạy vẫn ở lần thử hiện tại.",
        sending = "Đang gửi…",
        send = "Gửi hỏi tiếp",
    ),
)
