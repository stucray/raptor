package com.stucray.raptor.sources;

import java.util.List;
import java.util.Optional;

/**
 * The declared set of data sources raptor captures.
 *
 * <p>One list, consulted by everything that needs to know what a source is: the
 * health screen enumerates it, so a source that has never delivered still
 * appears rather than being invisible until its first run.
 */
public interface SourceRegistry {

    /** Every registered source, in a stable display order. */
    List<SourceAdapter> all();

    /** The adapter with this identity, if it is registered. */
    Optional<SourceAdapter> find(String id);
}
