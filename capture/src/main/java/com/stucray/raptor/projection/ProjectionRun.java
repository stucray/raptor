package com.stucray.raptor.projection;

/**
 * The job parameter that makes each launch of a projection its own
 * {@code JobInstance}.
 *
 * <p>Without it a projection runs once per unit per database and is refused ever
 * after, because {@code month} and {@code market} were the only identifying
 * parameters and Batch's {@code (JOB_NAME, JOB_KEY)} uniqueness is permanent
 * (#113). That made the ops surface refuse the one operation the architecture
 * says is the only way {@code query} is ever produced.
 *
 * <p>It is <em>identifying</em>, which is the whole point — a non-identifying
 * parameter does not enter {@code JOB_KEY} and would change nothing. What it
 * gives up is the accidental mutual exclusion that permanent uniqueness
 * provided; {@link ProjectionLock} is what replaces it, deliberately, with the
 * guarantee that was actually wanted.
 *
 * <p>Every attempt therefore keeps its own row in the run ledger, which is what
 * {@code batch} is for: "this month was re-projected three times, here is when
 * and how each went" is a better answer than "this month was projected once, in
 * a database that no longer exists".
 */
final class ProjectionRun {

	static final String PARAM = "run";

	/**
	 * Asks a projection to re-derive everything rather than only what can have
	 * changed since the last run.
	 *
	 * <p>Identifying, like {@link #PARAM}, so a full run and an incremental one at
	 * the same millisecond are distinct instances — and so the run ledger records
	 * which kind each execution was. An incremental projection that cannot be told
	 * apart from a rebuild in `batch` is one whose history stops explaining its
	 * output.
	 */
	static final String FULL_PARAM = "full";

	private ProjectionRun() {
	}
}
