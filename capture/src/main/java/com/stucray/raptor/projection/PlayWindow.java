package com.stucray.raptor.projection;

/**
 * When a match is in play, as SQL over {@code ledger.market_scope s}: the one
 * spelling every "during play" question shares.
 *
 * <p><b>Play starts at kickoff, not at the poll that noticed it (#16).</b>
 * {@code in_play_since} is dated by the catalogue poll, because the write path
 * may not parse a message to learn it sooner — so it trails the whistle by up
 * to a poll interval. Read as the start of play, it made a gap in the first
 * minutes of a match a gap with nothing in play: recorded, and never reported.
 * The scheduled kickoff is known in advance and needs no parse, so play starts
 * at whichever of the two is earlier. A match that kicks off late only widens
 * the window backwards, which is the safe direction for a question about loss.
 *
 * <p>{@code in_play_since} still decides WHETHER a market was ever in play, and
 * that part is not replaceable: a fixture postponed after its market was
 * created passes its kickoff without ever going in play, and a gap then cost
 * nothing.
 *
 * <p>{@code LEAST} ignores NULLs, so a market with no recorded kickoff falls
 * back to {@code in_play_since} alone, which is what the rule was before.
 *
 * <p>Held as text because it is spliced into several queries in two modules,
 * and two spellings of one rule drift. Every caller aliases the table {@code s}.
 */
public final class PlayWindow {

	/** Was this market ever in play. */
	public static final String WENT_IN_PLAY = "s.in_play_since is not null";

	/** When play began: the earlier of the scheduled kickoff and the first in-play poll. */
	public static final String STARTS = "least(s.kickoff, s.in_play_since)";

	private PlayWindow() {
	}
}
