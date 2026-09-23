package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Puts one spill file back into the system of record, exactly once.
 *
 * <p>The whole guarantee is an order of operations:
 *
 * <ol>
 *   <li>the ledger row and the file's messages are written in <b>one</b>
 *       transaction;
 *   <li>the file is unlinked <b>only after</b> that transaction commits.
 * </ol>
 *
 * <p>Crash between them and the file replays; the ledger's unique key on
 * {@code name} then refuses the second insert, the messages are not written
 * again, and the file is unlinked. Crash after them and the file is already
 * gone. There is no window in which a file is both deleted and not ingested,
 * and no case that needs a {@code delete} against {@code raw} — which matters,
 * because {@code raw} is append-only and an idempotency scheme that had to
 * delete from it would be repealing that rather than respecting it.
 *
 * <p>Out-of-order arrival is not a problem to solve: rows carry Betfair's
 * {@code pt} and a {@code seq} monotonic within the session, so a batch landing
 * ten minutes late sorts into place and nothing downstream depends on insertion
 * order.
 */
@Component
class SpillIngest {

	private static final Logger log = LoggerFactory.getLogger(SpillIngest.class);

	/** What happened to a file, for the log and the tests. */
	enum Outcome {
		/** Messages written and the ledger row claimed. */
		INGESTED,
		/** The ledger already had it: a previous run committed before it unlinked. */
		ALREADY_INGESTED,
		/** Nothing there — another run got to it first. */
		MISSING
	}

	record Result(Outcome outcome, int messages) {}

	private final JdbcClient jdbc;
	private final RawWriter writer;
	private final SpillDirectory directory;

	SpillIngest(@Acquisition JdbcClient acquisitionJdbcClient, RawWriter writer,
			SpillDirectory directory) {
		this.jdbc = acquisitionJdbcClient;
		this.writer = writer;
		this.directory = directory;
	}

	/** Must be called inside a transaction on the acquisition datasource. */
	Result ingest(String name) throws IOException, SQLException {
		Path file = directory.resolve(name);
		if (!Files.exists(file)) {
			return new Result(Outcome.MISSING, 0);
		}

		SpillFile.Contents contents = SpillFile.read(file);
		Optional<Long> claimed = claim(name, contents, Files.size(file));
		if (claimed.isEmpty()) {
			log.info("spill file {} was already ingested; removing it", name);
			unlinkAfterCommit(file);
			return new Result(Outcome.ALREADY_INGESTED, 0);
		}

		writer.write(contents.messages());
		unlinkAfterCommit(file);
		log.info("replayed {} message(s) from {} (spilled {}, cause {})",
				contents.messages().size(), name, contents.header().spilledAt(),
				contents.header().cause());
		return new Result(Outcome.INGESTED, contents.messages().size());
	}

	private Optional<Long> claim(String name, SpillFile.Contents contents, long bytes) {
		return jdbc.sql("""
						insert into raw.spill_file (name, session_id, cause, messages, bytes, spilled_at)
						values (?, ?, ?, ?, ?, ?)
						on conflict (name) do nothing
						returning id""")
				.params(name,
						contents.header().sessionId(),
						contents.header().cause().name(),
						contents.messages().size(),
						bytes,
						// OffsetDateTime, not Instant: pgjdbc cannot infer a SQL type for
						// an Instant parameter and throws.
						contents.header().spilledAt().atOffset(ZoneOffset.UTC))
				.query(Long.class)
				.optional();
	}

	/**
	 * The second half of the guarantee, and the reason this is a synchronisation
	 * rather than a line at the end of the method: a delete before the commit
	 * would destroy the only copy of messages that are not yet durable anywhere.
	 */
	private static void unlinkAfterCommit(Path file) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					Files.deleteIfExists(file);
				} catch (IOException e) {
					// Harmless: the file replays, conflicts on the ledger's unique key,
					// writes nothing, and is deleted again. Loud, because a spill
					// directory that cannot be emptied fills the disk that the next
					// outage depends on.
					log.error("could not remove ingested spill file {}", file, e);
				}
			}
		});
	}
}
