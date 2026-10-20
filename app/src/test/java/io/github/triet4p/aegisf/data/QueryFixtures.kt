package io.github.triet4p.aegisf.data

/**
 * The bounded reads a host test uses to look at the whole stored state.
 *
 * The shared query surface has no unbounded form, so a test that wants to see every stored
 * row asks for the largest page the surface allows and reads that page's items. These
 * constants are that request, written once so every test states the same scope.
 */
internal val ALL_LEDGER_RECORDS: LedgerRecordQuery =
    LedgerRecordQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE))

/** Every stored category, archived ones included. */
internal val ALL_CATEGORIES: CategoryQuery =
    CategoryQuery(state = CategoryState.ALL, bounds = QueryPageRequest(LIST_PAGE_SIZE))

/** Only the categories a new record may be filed under. */
internal val ALL_ASSIGNABLE_CATEGORIES: CategoryQuery =
    CategoryQuery(state = CategoryState.ACTIVE, bounds = QueryPageRequest(LIST_PAGE_SIZE))

/** Every stored bank draft, in every lifecycle state. */
internal val ALL_DRAFTS: DraftCandidateQuery =
    DraftCandidateQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE))
