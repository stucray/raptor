package com.stucray.raptor.sources;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One registered data source, as capture sees it.
 *
 * <p>paddock's adapter had two owners per source — who puts raw on disk and who
 * turns it into rows — because paddock did both. raptor does only the first
 * (PRD #309): turning raw into rows is overround-analysis's projection, and a
 * registry here that named its jobs would be describing another application's
 * schedule. So an adapter says where the source's raw lives, what writes it,
 * and how to tell how recently it did.
 *
 * @param id the source's identity everywhere in the system
 * @param description what the source is, in one line
 * @param custodyPath where the files this source is kept as live, under the
 *     configured data root. A path for a human reading the screen, not one this
 *     application walks
 * @param acquisition who puts the raw into the system of record, and on whose
 *     schedule
 * @param jobs the Spring Batch jobs that load this source into {@code raw}, whose
 *     executions are its run history. Empty for a source written by a resident
 *     component rather than a job — the live recorder, whose record is its
 *     sessions
 * @param freshness where to read when this source last delivered, or null when
 *     it has no meaningful high-water mark
 */
public record SourceAdapter(
    String id,
    String description,
    String custodyPath,
    ScheduleOwner acquisition,
    List<String> jobs,
    @Nullable Freshness freshness) {

    public SourceAdapter {
        jobs = List.copyOf(jobs);
    }

    /**
     * The latest {@code column} across {@code table}. Both are registry-declared
     * identifiers, never user input — they are interpolated into SQL — and both
     * are capture's own records, never {@code query}: when a source last
     * delivered is a fact about capture, and it must not go blank because
     * overround-analysis has not projected yet.
     */
    public record Freshness(String table, String column) {}
}
