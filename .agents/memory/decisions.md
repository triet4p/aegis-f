# Project decisions

## [2026-10-17] Use metadata-ranked exact-budget category candidates

**Decision:** Keep candidate planning over current `CategoryEntity` ids/names, apply caller-supplied exact-description corrections and unique category-name phrases before inference, and use the pinned tokenizer/sequence budget plus deterministic name-evidence ranking for oversized sets; represent no-fit as a typed unknown rather than an id sentinel.

**Alternatives considered:** Selecting the first N categories regardless of evidence; assigning unknown a reserved numeric id; expanding the category schema with aliases/descriptions in S4.4; persisting correction rules before S4.7.

**Reason:** The active category schema currently contains only stable ids, names, and archive state. A count-based prefix can omit relevant categories without evidence, while exact token measurement preserves the pinned sequence contract. Keeping correction inputs caller-owned allows S4.7 to add deliberate persistence without automatic learning now.

**Consequences:** Oversized retrieval is limited to lexical category-name evidence and may return manual unknown for semantically relevant labels that do not overlap. Results must be resolved by stable id and unchanged active name against current rows; clients must surface omitted-category uncertainty and must not write or confirm a record from this result.
