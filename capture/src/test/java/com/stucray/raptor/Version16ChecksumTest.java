package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.datasource.Acquisition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The inherited migration history matches the live database's except at V16,
 * and V16's difference is exactly the one the cutover repairs.
 *
 * <p>raptor inherits paddock's acquisition migrations so that every checksum
 * the live database recorded still matches and nothing re-runs. One file could
 * not be inherited byte for byte: V16's comment quoted two real market ids, and
 * raptor is public. Its SQL is unchanged, but Flyway's checksum is a CRC32 over
 * every line, comments included, so the cutover (#323) must update V16's row in
 * {@code query.flyway_schema_history_acquisition} from {@link #LIVE_V16} to
 * {@link #RAPTOR_V16} before raptor's Flyway validates.
 *
 * <p>These numbers are read from what Flyway itself recorded when it migrated
 * this test's database, so the runbook's target is Flyway's own computation, not
 * a reimplementation of it. If V16 is ever edited again this fails, and the
 * runbook's number has to move with it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("The inherited migrations carry the checksums the cutover expects")
class Version16ChecksumTest {

	/** What the live database recorded for V16, migrated from paddock's copy. */
	static final int LIVE_V16 = 2006155428;

	/** What raptor's rewritten V16 produces, and what the cutover writes. */
	static final int RAPTOR_V16 = 1008014348;

	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("V16's checksum is the value the cutover writes, and differs from the live one")
	void version16() {
		Integer recorded = jdbc.sql("""
						select checksum from query.flyway_schema_history_acquisition
						where version = '16'""")
				.query(Integer.class).single();

		assertThat(recorded).isEqualTo(RAPTOR_V16);
		// The witness: if these were equal there would be nothing to repair, and
		// a runbook step that updates a row to the value it already holds would
		// pass while proving nothing.
		assertThat(RAPTOR_V16).isNotEqualTo(LIVE_V16);
	}

	/**
	 * The other 32 inherited migrations (V1-V33) are byte-identical to paddock's,
	 * so their checksums are the live database's. Pinned from the live history on
	 * 2026-09-23. Later versions are raptor's own and are not inherited.
	 */
	@Test
	@DisplayName("every other inherited migration keeps the live database's checksum")
	void everyOtherVersionMatchesTheLiveDatabase() {
		List<String> recorded = jdbc.sql("""
						select version || ':' || checksum from query.flyway_schema_history_acquisition
						where version is not null and version <> '16'
							and version::int <= 33
						order by installed_rank""")
				.query(String.class).list();

		assertThat(recorded).containsExactlyElementsOf(LIVE_CHECKSUMS);
	}

	private static final List<String> LIVE_CHECKSUMS = List.of(
			"1:-110027536", "2:-1546873987", "3:526152354", "4:369490395",
			"5:101127759", "6:-1592062341", "7:-1716632862", "8:-1672366133",
			"9:-638778736", "10:778478982", "11:-849278283", "12:-329987824",
			"13:-374784973", "14:958257481", "15:368543885", "17:-1162659112",
			"18:1976632766", "19:2015888885", "20:1059950627", "21:1442425077",
			"22:1878167196", "23:-1252069621", "24:1309126010", "25:-1237040543",
			"26:-206148799", "27:230471468", "28:-1398820104", "29:638940740",
			"30:-942597064", "31:-1531107158", "32:1279127067", "33:-1850228933"
	);
}
