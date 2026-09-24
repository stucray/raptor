package com.stucray.raptor.sources;

/**
 * Who puts a source's raw into the system of record, and on whose schedule.
 *
 * <p>Every owner is raptor. paddock's enum also named overround-analysis's
 * close-out, as the schedule that projected two of the sources; projection is
 * not capture's, so that value did not come across (PRD #309).
 */
public enum ScheduleOwner {

    /** The resident recorder, which holds the advisory capture lease. */
    RECORDER("raptor's resident recorder (advisory capture lease)"),

    /**
     * The nightly close-out, whose one step is the football-data.co.uk fetch.
     * Fired over {@code POST /ops/close-out} by a launchd agent (paddock#334).
     */
    CLOSE_OUT("raptor's nightly close-out (fetchArchiveJob)"),

    /**
     * No schedule: a corpus downloaded once and thereafter static, or a run a
     * person asks for. Not a gap — a fact about the source.
     */
    ON_DEMAND("no schedule — operator initiated");

    private final String owner;

    ScheduleOwner(String owner) {
        this.owner = owner;
    }

    /** A plain description of who runs it. */
    public String owner() {
        return owner;
    }
}
