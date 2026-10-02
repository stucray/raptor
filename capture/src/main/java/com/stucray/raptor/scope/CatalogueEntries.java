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
	 * Store each market's entry from one response, if the market has none yet.
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
						  and not exists (
							select 1 from raw.market_catalogue c
							where c.market_id = e->>'marketId')""")
				// OffsetDateTime, not Instant: pgjdbc cannot infer a type for an
				// Instant parameter.
				.params(fetchedAt.atOffset(ZoneOffset.UTC), response)
				.update();
	}

	/**
	 * How many markets in scope have no entry kept yet (#44).
	 *
	 * <p>The number that matters while it can still change: once a market closes,
	 * Betfair no longer serves its entry, so a fetch that keeps failing has to be
	 * visible before then or the entry is lost for good.
	 */
	int inScopeWithoutEntry() {
		Long count = jdbc.sql("""
						select count(*) from raw.market_scope s
						where s.state <> 'DONE'
						  and not exists (
							select 1 from raw.market_catalogue c
							where c.market_id = s.market_id)""")
				.query(Long.class).single();
		return Math.toIntExact(count);
	}
}
