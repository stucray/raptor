package com.stucray.raptor.projection;

/**
 * Which markets a session's connection carried, as SQL over the ledger: the one
 * spelling every per-connection question shares (#66).
 *
 * <p>A gap, a restart or an abandoned session belongs to ONE connection, and since
 * #65 there can be several. Asked across every market in scope, as it was while
 * there was only one connection, a gap on slot 1 would be charged with every
 * market slot 0 was recording perfectly well.
 *
 * <p><b>NULL is slot 0.</b> A row recorded before connection slots existed has no
 * slot, and there was one connection then. Reading NULL as slot 0 on both sides
 * makes every row from before slots match every other, so each answer from that
 * era is exactly what it was. A PENDING market holds no slot either, and is
 * charged to slot 0 the same way, which is where the single connection would
 * have charged it.
 *
 * <p>A market's slot is the one that carries it, or last carried it. Assignment
 * is sticky — a fixture stays on its slot until it leaves scope (#64) — so that
 * is the slot it was on for the whole of its time in scope, except in the one
 * case the planner moves it: a slot no longer allowed after lowering
 * {@code max-connections}.
 *
 * <p>Every caller aliases the market {@code s}; the session's slot is passed as
 * the column or expression that holds it.
 */
public final class ConnectionSlot {

	/**
	 * The market {@code s} was on the connection whose slot is {@code slot}.
	 *
	 * @param slot a SQL expression for the session's {@code connection_slot}
	 */
	public static String carries(String slot) {
		return "coalesce(s.connection_slot, 0) = coalesce(" + slot + ", 0)";
	}

	private ConnectionSlot() {
	}
}
