package com.stucray.raptor.scope;

/**
 * How much there is to capture right now.
 *
 * <p>Published beside {@link CaptureScope} rather than added to it, and the
 * distinction is the whole point: {@code CaptureScope} is what the recorder
 * asks in order to <em>subscribe</em>, and widening it would invite the
 * recorder to start making scope decisions of its own. This says only how many
 * markets there are and when the next one starts — the fact everything outside
 * scope needs to judge whether a recorder that is not recording is correct or
 * is an incident (#131).
 */
public interface ScopeCensus {

	/** Count what is in scope, from the live ledger. */
	ScopeSummary summarise();
}
