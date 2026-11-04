package io.github.triet4p.aegisf.data

/** The shared local boundary for every non-user automatic attachment. */
internal class AutomaticAttachmentPolicy(database: LedgerDatabase) {
    private val draftDao = database.pendingBankDraftDao()
    private val eventDao = database.noteEventDao()
    private val linkEvidence = LinkEvidencePolicy(database)
    private val queries = WorkflowQueryService(database)

    /**
     * Returns current unique evidence only when a bounded grant still covers every pending draft.
     * Retrieval ranking is deliberately not attachment authority.
     */
    suspend fun trustedEvidence(
        eventId: Long,
        draftId: Long,
        authorizedDraftIds: Set<Long>,
        pendingDatabaseComplete: Boolean,
    ): TrustedLinkEvidence? {
        val event = eventDao.findById(eventId) ?: return null
        val draft = draftDao.findById(draftId) ?: return null
        return trustedEvidence(event, draft, authorizedDraftIds, pendingDatabaseComplete)
    }

    /** Returns current unique evidence only when a bounded grant still covers every pending draft. */
    suspend fun trustedEvidence(
        event: NoteEventEntity,
        draft: PendingBankDraftEntity,
        authorizedDraftIds: Set<Long>,
        pendingDatabaseComplete: Boolean,
    ): TrustedLinkEvidence? {
        if (authorizedDraftIds.size > QueryPageRequest.MAX_PAGE_SIZE || draft.id !in authorizedDraftIds) {
            return null
        }
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return null
        }
        val candidateSearch = queries.matchDraftCandidates(
            eventId = event.id,
            authorizedDraftIds = authorizedDraftIds,
            pendingDatabaseComplete = pendingDatabaseComplete,
        ) ?: return null
        if (!candidateSearch.pendingDatabaseComplete) return null
        return linkEvidence.evidenceFor(event, draft)
            .takeIf { evidence -> evidence.verdict == LinkEvidenceVerdict.UNIQUE }
    }
}
