package com.stucray.raptor.archive;

import com.stucray.raptor.datasource.Acquisition;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code raw.football_file}: what has been fetched, and what to revalidate with.
 *
 * <p>The table is append-only in the sense that matters — <b>content is never
 * rewritten</b>. A path whose bytes change gains a row beside the old one, so
 * the version an analysis ran against survives the correction that replaced it
 * on disk. What a re-fetch may update is the validators and {@code checked_at},
 * which are the cache key for the next request rather than anything derived from
 * the file.
 *
 * <p>The {@link Acquisition} qualifier is not decoration: the {@code @Primary}
 * client belongs to the read identity, which holds no grant on {@code raw} at
 * all.
 */
@Component
class FootballFiles {

	/** What the system of record already has for a path, given some bytes. */
	enum Held {
		/** No version of this file has ever been fetched. */
		ABSENT,
		/** These exact bytes are already stored — the fetch found nothing new. */
		SAME_BYTES,
		/** A different version is stored: upstream has republished the file. */
		DIFFERENT_BYTES
	}

	private final JdbcClient jdbc;

	FootballFiles(@Acquisition JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The newest version's validators, or empty for a path never fetched. */
	Optional<Validators> validatorsFor(String path) {
		return jdbc.sql("""
				select etag, last_modified
				from raw.football_file
				where path = ?
				order by fetched_at desc, id desc
				limit 1""")
				.param(path)
				.query((rs, row) -> new Validators(rs.getString("etag"), rs.getString("last_modified")))
				.optional();
	}

	Held classify(String path, byte[] sha256) {
		return jdbc.sql("""
				select
					count(*) filter (where sha256 = ?) > 0 as same,
					count(*) > 0                          as has_any
				from raw.football_file where path = ?""")
				.params(sha256, path)
				.query((rs, row) -> {
					if (rs.getBoolean("same")) {
						return Held.SAME_BYTES;
					}
					return rs.getBoolean("has_any") ? Held.DIFFERENT_BYTES : Held.ABSENT;
				})
				.single();
	}

	/** Store a version of a file the system of record did not have. */
	void insert(ArchiveTarget target, byte[] sha256, byte[] content, Validators validators) {
		jdbc.sql("""
				insert into raw.football_file (
					path, division, season, sha256, bytes, content, etag, last_modified)
				values (?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(Arrays.asList(target.path(), target.division(), target.season(),
						sha256, (long) content.length, content,
						validators.etag(), validators.lastModified()))
				.update();
	}

	/**
	 * Note that a version we already hold is still the current one.
	 *
	 * <p>Called for a {@code 304}, and for the rarer case of an unconditional GET
	 * that came back byte-identical — the first sweep after a restore, say. The
	 * validators move forward even though the content did not, because a server
	 * that rotates an {@code ETag} without changing a byte would otherwise make
	 * every future sweep re-download the same file.
	 */
	void confirm(String path, Validators validators) {
		jdbc.sql("""
				update raw.football_file set
					etag = coalesce(?, etag),
					last_modified = coalesce(?, last_modified),
					checked_at = now()
				where id = (select id from raw.football_file
				            where path = ? order by fetched_at desc, id desc limit 1)""")
				.params(Arrays.asList(validators.etag(), validators.lastModified(), path))
				.update();
	}
}
