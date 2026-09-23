package com.stucray.raptor.ingest;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Decides what a capture file is, and reads it if it is worth reading.
 *
 * <p>Three outcomes, and the middle one is the reason this class exists:
 *
 * <ul>
 *   <li><b>Already loaded</b> — a row on {@code raw.capture_file} for this path.
 *       Skipped, so a re-run is a no-op rather than a duplicate.
 *   <li><b>Superseded</b> — paddock's own resident recorder captured this market
 *       too, on the shadow night. Loading the file as well would put <em>two
 *       observations of one market</em> into the system of record, and the
 *       projection reads a market's messages by market id alone: it would splice
 *       two connections' packaging into one parse, which is precisely the
 *       comparison the shadow diff proved cannot be made. paddock's own
 *       recording wins — it is the one the recorder can vouch for — and the file
 *       is recorded as superseded rather than passed over in silence.
 *   <li><b>Loaded</b> — everything else, which is every market the Python
 *       captured before the recorder existed.
 * </ul>
 */
class CaptureFileProcessor implements ItemProcessor<Path, CaptureLoad> {

	private static final Logger log = LoggerFactory.getLogger(CaptureFileProcessor.class);

	private final CaptureCorpusScanner scanner;
	private final CaptureMessageExtractor extractor;
	private final JdbcClient jdbc;
	private final Set<String> residentMarkets;
	private final ImportedSessions sessions;

	CaptureFileProcessor(CaptureCorpusScanner scanner, CaptureMessageExtractor extractor,
			JdbcClient jdbc) {
		this.scanner = scanner;
		this.extractor = extractor;
		this.jdbc = jdbc;
		this.residentMarkets = Set.copyOf(jdbc.sql("""
						select distinct m.market_id
						from raw.stream_message m
						join raw.capture_session s on s.id = m.session_id
						where s.source_key is null""")
				.query(String.class)
				.list());
		this.sessions = ImportedSessions.load(jdbc);
		log.info("{} imported session(s); {} market(s) already held by the resident recorder",
				sessions.size(), residentMarkets.size());
	}

	@Override
	public @Nullable CaptureLoad process(Path file) throws Exception {
		CapturedFile described = scanner.describe(file);
		if (alreadyLoaded(described.relativePath())) {
			return null;
		}
		if (residentMarkets.contains(described.marketId())) {
			return new CaptureLoad(described, CaptureLoad.SUPERSEDED,
					extractor.header(file), List.of());
		}
		CaptureContents contents = extractor.extract(file, sessions);
		return new CaptureLoad(described, CaptureLoad.LOADED,
				contents.metaJson(), contents.messages());
	}

	private boolean alreadyLoaded(String path) {
		Long rows = jdbc.sql("select count(*) from raw.capture_file where path = ?")
				.param(path)
				.query(Long.class)
				.single();
		return rows != null && rows > 0;
	}

	/** Messages that arrived before any known session had started. */
	int outOfWindow() {
		return sessions.outOfWindow();
	}
}
