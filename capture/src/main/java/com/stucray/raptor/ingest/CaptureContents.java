package com.stucray.raptor.ingest;

import com.stucray.raptor.rawstore.RawMessage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one Python-era capture file holds: its messages, and the header line
 * that is not one.
 *
 * @param metaJson the {@code _meta} line verbatim, or null when the file has
 *     none. It is recorded on {@code raw.capture_file} rather than turned into
 *     a message, because it never came off the wire — but it carries the event
 *     and competition names the Python resolved, and the read side's fixture
 *     join is built on them.
 */
record CaptureContents(@Nullable String metaJson, List<RawMessage> messages) {}
