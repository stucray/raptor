/**
 * Whether this instance is actually capturing what it is supposed to capture.
 *
 * <p>One judgement, and it exists as its own module because it needs two others
 * to make it: what the recorder is doing ({@code recorder}) and how much there
 * is to do ({@code scope}). Neither of those may see the other — the recorder
 * asking scope questions is how the twelve-hour window became impossible to
 * move away from, and scope reaching into the write path would put a decision
 * on the socket-draining thread. So the pair is composed here instead, where
 * both are already published API and neither learns anything.
 *
 * <p>Nothing in this package writes, schedules or subscribes. It reads two
 * facts and returns a verdict.
 */
package com.stucray.raptor.capture;
