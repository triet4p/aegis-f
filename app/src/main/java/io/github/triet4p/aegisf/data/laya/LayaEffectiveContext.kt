package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.effectiveInputDigest
import io.github.triet4p.aegisf.data.isBlockedForSuggestionSafety

/**
 * One stored reading that is relevant to a classification request.
 *
 * A reading is the interpretation of ONE span of one capture, never the whole capture: a
 * note with several events contributes only the events that are actually linked to the draft
 * being reviewed, so a multi-event note is never copied into every draft.
 */
data class LayaEventReading(
    /** The stored event this reading came from. */
    val eventId: Long,
    /** The reading's own text — the span's interpretation, not the capture's whole body. */
    val text: String,
)

/**
 * The text one classification request is made against.
 *
 * [primaryText] is the text the request is fundamentally about: the user's own description in
 * manual entry, and the review form's permitted bank description (or its explicit note) in a
 * bank draft review. [eventReadings] are the stored user event readings that are relevant to
 * the same draft; they are added to the primary text by [LayaEffectiveContextComposer] under
 * the model's actual input budget, in a fixed order, and never replace the primary text.
 */
data class LayaEffectiveContext(
    val primaryText: String,
    val eventReadings: List<LayaEventReading> = emptyList(),
) {
    companion object {
        /** A request made against one description alone, with no stored context. */
        fun of(description: String): LayaEffectiveContext = LayaEffectiveContext(description)
    }
}

/**
 * The composed input one request actually carried.
 *
 * [text] is what the model saw; [includedReadings] and [droppedReadings] say which stored
 * readings were part of it and which were left out, so an omitted reading is visible rather
 * than silently lost. [tokenCount] is the exact size of [text] in the pinned tokenizer's
 * tokens, and [roomTokens] is the room the composer was allowed to use.
 *
 * [refusal] is set when the composed input may not be offered to the model at all: the
 * primary text alone is larger than the room the model leaves for its state. The text is
 * then kept exactly as the caller supplied it — it is never shortened to fit — and the
 * request is refused instead of being asked over budget.
 */
data class LayaEffectiveInput(
    val text: String,
    /** The digest of [text]; the composed text itself is never stored. */
    val digest: String,
    val includedReadings: List<LayaEventReading>,
    val droppedReadings: List<LayaEventReading>,
    val tokenCount: Int,
    val roomTokens: Int,
    /** Why this input may not be offered to the model; null when it fits the room. */
    val refusal: Refusal? = null,
) {
    /** Why a composed input cannot be offered to the model. */
    enum class Refusal {
        /**
         * The primary text alone is larger than the room the model leaves for its state.
         * The text is not shortened and no reading is added: the request is refused.
         */
        PRIMARY_EXCEEDS_ROOM,
    }

    /** True when the composed input fits the room and may be offered to the model. */
    val fitsRoom: Boolean get() = refusal == null
}

/**
 * The model evaluation of one request: the categories the model actually evaluated, in the
 * order it evaluated them, with the calibrated share of each, the calibrated no-fit share,
 * and the one Jev sharpness value of the same distribution.
 *
 * A direct decision (an explicit correction, a category-name match, or a saved rule) has no
 * evaluation: it never ran the model, so it carries no share and no sharpness.
 */
data class LayaEvaluation(
    val categoryIds: List<Long>,
    val probabilities: List<Double>,
    val unknownShare: Double,
    val confidence: Double,
)

/**
 * One completed review-only decision together with what produced it.
 *
 * The decision itself is the planner's own result; [effectiveInput] says what the model was
 * asked about, and [evaluation] carries the evaluated set and its shares when the model ran.
 */
data class LayaContextDecision(
    val decision: LayaCandidatePlanner.Decision,
    val effectiveInput: LayaEffectiveInput,
    val evaluation: LayaEvaluation?,
)

/**
 * Builds the effective input of a classification request.
 *
 * The composition is deterministic and bounded by the model's ACTUAL input budget:
 *
 * - the primary text comes first and is never rewritten, shortened, or reordered — the
 *   permitted bank description (or the user's own description in manual entry) stays
 *   exactly as it was;
 * - the stored event readings follow in the order the caller supplied them (the storage
 *   layer supplies them by ascending event id), each separated by a single space;
 * - a reading is added only while the composed text still fits the room the model leaves
 *   for its state, so the composed input is never longer than the model can accept; a
 *   reading that does not fit — or that the safety guard refuses — is left out and reported
 *   in [LayaEffectiveInput.droppedReadings] instead of being silently truncated into the
 *   text;
 * - the room is the room every candidate set leaves: the sequence's total budget minus the
 *   largest header the model may build, minus the four structural tokens around the state.
 *   Because the header never exceeds that bound, a composed input that fits the room is
 *   always carried by the model in full;
 * - a primary text that is larger than the room on its own cannot be offered to the model
 *   at all: it is returned unchanged, with
 *   [LayaEffectiveInput.Refusal.PRIMARY_EXCEEDS_ROOM] set, so the caller refuses the
 *   request instead of asking the model over budget. A primary text that exactly fills the
 *   room does fit and is offered as it is.
 */
object LayaEffectiveContextComposer {

    /**
     * The room the effective input may use, in tokens, for any candidate set.
     *
     * The sequence is `[CLS] header [SEP] options [SEP] state [SEP]`: the state may use
     * everything the total budget leaves after the header, the option markers, and those
     * four structural tokens. The header never exceeds the pinned head budget, so this room
     * is the one every candidate set leaves.
     */
    fun guaranteedRoomTokens(maxLen: Int, headMaxLen: Int): Int =
        maxOf(0, maxLen - headMaxLen - 4)

    /**
     * Composes the effective input of one request.
     *
     * The composed text is measured with the pinned tokenizer, so [LayaEffectiveInput.tokenCount]
     * is the exact size of what the model receives, not an estimate.
     */
    fun compose(
        tokenizer: LayaTokenizer,
        context: LayaEffectiveContext,
        maxLen: Int = LayaRuntimeContract.MAX_LEN,
        headMaxLen: Int = LayaRuntimeContract.HEAD_MAX_LEN,
    ): LayaEffectiveInput {
        val room = guaranteedRoomTokens(maxLen, headMaxLen)
        val primary = context.primaryText
        val primaryTokens = tokenizer.encode(LayaSequence.scrub(primary)).size
        if (primaryTokens > room) {
            // The primary text alone is larger than the room the model leaves for its state:
            // the request cannot be asked without going over the model's actual input
            // budget. The text is kept exactly as the caller supplied it — it is never
            // shortened to fit — and the request is refused instead.
            return LayaEffectiveInput(
                text = primary,
                digest = effectiveInputDigest(primary),
                includedReadings = emptyList(),
                droppedReadings = context.eventReadings,
                tokenCount = primaryTokens,
                roomTokens = room,
                refusal = LayaEffectiveInput.Refusal.PRIMARY_EXCEEDS_ROOM,
            )
        }
        if (primaryTokens == room) {
            // The primary text alone exactly fills the room: it fits, so it is offered as it
            // is, and no stored reading is added because no reading fits any more.
            return LayaEffectiveInput(
                text = primary,
                digest = effectiveInputDigest(primary),
                includedReadings = emptyList(),
                droppedReadings = context.eventReadings,
                tokenCount = primaryTokens,
                roomTokens = room,
            )
        }
        val included = ArrayList<LayaEventReading>(context.eventReadings.size)
        val dropped = ArrayList<LayaEventReading>()
        var text = primary
        var tokens = primaryTokens
        for (reading in context.eventReadings) {
            val readingText = reading.text.trim()
            if (readingText.isEmpty() || isBlockedForSuggestionSafety(readingText)) {
                dropped.add(reading)
                continue
            }
            val candidate = "$text $readingText"
            val candidateTokens = tokenizer.encode(LayaSequence.scrub(candidate)).size
            if (candidateTokens > room) {
                dropped.add(reading)
                continue
            }
            text = candidate
            tokens = candidateTokens
            included.add(reading)
        }
        return LayaEffectiveInput(
            text = text,
            digest = effectiveInputDigest(text),
            includedReadings = included,
            droppedReadings = dropped,
            tokenCount = tokens,
            roomTokens = room,
        )
    }
}
