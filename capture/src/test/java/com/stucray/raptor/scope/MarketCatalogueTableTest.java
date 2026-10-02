package com.stucray.raptor.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V40 (#42): {@code raw.market_catalogue} is shaped and granted like the rest of
 * {@code raw}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("raw.market_catalogue is keyed for integrity and read by whoever reads raw")
class MarketCatalogueTableTest {

	private static final String ACL = """
			select string_agg(x::text, ',' order by x::text)
			from pg_class c, unnest(c.relacl) x where c.oid = cast(? as regclass)""";

	@Autowired @Acquisition JdbcClient jdbc;

	/**
	 * Counted, not assumed from default privileges: the grant a new table in
	 * {@code raw} receives must be exactly the grant the stream has, no more.
	 */
	@Test
	@DisplayName("its grants are exactly raw.stream_message's")
	void grantedLikeTheStream() {
		String stream = jdbc.sql(ACL).param("raw.stream_message").query(String.class).single();
		// The witness: the stream's ACL carries a reader as well as its owner, so
		// equal ACLs below mean the reader was granted, not that nobody was.
		assertThat(stream.split(",")).hasSizeGreaterThan(1);

		assertThat(jdbc.sql(ACL).param("raw.market_catalogue").query(String.class).single())
				.isEqualTo(stream);
	}

	@Test
	@DisplayName("one key for identity and one for integrity, and nothing else indexed")
	void keyedForIntegrityOnly() {
		assertThat(jdbc.sql("""
						select pg_get_indexdef(indexrelid) from pg_index
						where indrelid = 'raw.market_catalogue'::regclass""")
				.query(String.class).list())
			.containsExactlyInAnyOrder(
					"CREATE UNIQUE INDEX market_catalogue_pkey ON raw.market_catalogue USING btree (id)",
					"CREATE UNIQUE INDEX market_catalogue_market_fetched_key ON raw.market_catalogue "
							+ "USING btree (market_id, fetched_at)");
	}

	@Test
	@DisplayName("the table and every column say what they hold")
	void everyColumnIsDescribed() {
		assertThat(jdbc.sql("select obj_description('raw.market_catalogue'::regclass, 'pg_class')")
				.query(String.class).single()).contains("verbatim").contains("Markets captured");
		assertThat(jdbc.sql("""
						select count(*) from pg_attribute
						where attrelid = 'raw.market_catalogue'::regclass and attnum > 0
						  and not attisdropped
						  and col_description(attrelid, attnum) is null""")
				.query(Long.class).single()).isZero();
	}
}
