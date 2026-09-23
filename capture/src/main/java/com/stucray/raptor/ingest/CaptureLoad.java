package com.stucray.raptor.ingest;

import com.stucray.raptor.rawstore.RawMessage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One capture file, ready to be written: its provenance row and the messages
 * that belong to it.
 *
 * @param status {@code LOADED}, or {@code SUPERSEDED} when paddock's own
 *     recorder already holds this market and the messages are therefore not
 *     written — see {@link CaptureFileProcessor}
 */
record CaptureLoad(
		CapturedFile file,
		String status,
		@Nullable String metaJson,
		List<RawMessage> messages) {

	static final String LOADED = "LOADED";
	static final String SUPERSEDED = "SUPERSEDED";
}
