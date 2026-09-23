package com.stucray.raptor.recorder;

import java.time.Instant;

/**
 * One newline-delimited frame off a stream source, with our clock at receipt.
 *
 * <p>{@code receivedAt} is stamped by whatever produced the frame, as close to
 * the read as possible: it and Betfair's own {@code pt} disagree, and the
 * disagreement is the only measure of our own latency there will ever be.
 *
 * @param json the frame verbatim, exactly as it arrived — never a parsed
 *     structure, because nothing on this path is allowed to depend on a parse
 * @param receivedAt our clock when the frame was read
 */
public record StreamFrame(String json, Instant receivedAt) {}
