package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import java.util.List;

/**
 * Where a message goes when Postgres is up and the <em>row</em> is what it will
 * not take.
 *
 * <p>The sibling of {@link SpillSink}, and the distinction between them is the
 * whole of #271. A spill is for a database that cannot answer: the batch is
 * intact, the retry will work, and waiting is the only thing that costs
 * anything. A quarantine is for a database that answered "no": the batch is
 * intact, the retry can <b>never</b> work, and retrying is what costs — the
 * drain replays the same doomed file every minute forever and the disk fills
 * behind it.
 *
 * <p>An implementation holds the messages whole, so that the cause can be fixed
 * and the rows replayed by hand. A row it holds is <b>not</b> in the system of
 * record, and that is the finding.
 */
interface Quarantine {

	/**
	 * Hold messages the database refused on their own merits.
	 *
	 * @param messages the refused messages, whole
	 * @param sqlState the SQLSTATE the server gave, which is what classified them
	 * @param failure the driver's message, for whoever reads the row later
	 * @return whether they are now held; {@code false} means the caller must fall
	 *     back to spilling, because losing them is not an option this system has
	 */
	boolean hold(List<RawMessage> messages, String sqlState, String failure);

	/**
	 * How many messages are held, durably, across every process that ever held
	 * one.
	 *
	 * <p>On the interface rather than read from the database by the health
	 * indicator, so that "how many" and "hold this" cannot disagree about where
	 * the answer lives — and so that a health test does not need a database to
	 * assert on a number.
	 *
	 * @return the count, or -1 if it cannot be determined right now
	 */
	long quarantined();
}
