package com.stucray.raptor.scope;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code raw.market_catalogue}: each scoped market's catalogue entry, kept as
 * Betfair sent it (#42).
 *
 * <p><b>The response is split in the database, not here.</b> PostgreSQL reads the
 * body as {@code jsonb} and hands back one element per market, so every value is
 * stored as Betfair spelled it. Decoding it in Java and encoding it again could
 * re-spell a number, and the stored entry would then be raptor's rendering.
 */
@Component
class CatalogueEntries {

	private final JdbcClient jdbc;

	CatalogueEntries(@Acquisition JdbcClient acquisitionJdbcClient) {
		this.jdbc = acquisitionJdbcClient;
	}

	/**
	 * Store each market's entry from one response, when it differs from the
	 * market's most recent stored entry (#43), or the market has none (#42).
	 *
	 * <p><b>"Differs" is decided by the database, against what is stored.</b> Not
	 * against a copy held here, which a restart would lose and which could drift
	 * from the table. The comparison is {@code jsonb} equality, which already sets
	 * key order and whitespace aside, so a row means Betfair changed what it said,
	 * never only how it laid it out. A market with no row compares against NULL,
	 * so its first entry is always written. One statement per response, however
	 * many markets it holds.
	 *
	 * @param response a {@code listMarketCatalogue} body: a JSON array of entries
	 * @param fetchedAt when it was asked for
	 * @return the entries written
	 */
	int record(String response, Instant fetchedAt) {
		return jdbc.sql("""
						insert into raw.market_catalogue (market_id, fetched_at, entry)
						select e->>'marketId', ?, e
						from jsonb_array_elements(cast(? as jsonb)) e
						where e->>'marketId' is not null
						  and e is distinct from (
							select c.entry from raw.market_catalogue c
							where c.market_id = e->>'marketId'
							order by c.fetched_at desc
							limit 1)""")
				// OffsetDateTime, not Instant: pgjdbc cannot infer a type for an
				// Instant parameter.
				.params(fetchedAt.atOffset(ZoneOffset.UTC), response)
				.update();
	}
}
