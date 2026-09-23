package com.stucray.raptor.copy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class CopyBufferTest {

	/**
	 * The regression that cost S3 an afternoon. COPY parses a bare timestamp into
	 * a {@code timestamptz} using the session's {@code TimeZone} — which pgjdbc
	 * takes from the JVM — so a value rendered in UTC without an offset lands
	 * shifted by however far the developer happens to be from Greenwich. Nothing
	 * fails: not the COPY, not a row count, not a sha256. The only way to see it
	 * is to look at a stored instant, and S2 had no reason to.
	 *
	 * <p>Asserted on the rendered text rather than through a database, so it
	 * holds in CI (UTC, where the bug is invisible) as firmly as it does here.
	 */
	@Test
	void rendersInstantsWithAnExplicitUtcOffset() {
		String rendered = render(new CopyBuffer(1).add(Instant.parse("2020-03-09T14:53:24.518Z")));

		// `XXX` spells a zero offset as `Z`, which PostgreSQL reads as UTC. What
		// matters is that an offset is present at all: without one the value is
		// interpreted in the session's timezone.
		assertThat(rendered).isEqualTo("2020-03-09 14:53:24.518000Z");
	}

	@Test
	void rendersInstantsIndependentlyOfTheDefaultTimeZone() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
			String bangkok = render(new CopyBuffer(1).add(Instant.parse("2020-03-09T14:53:24.518Z")));
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			String utc = render(new CopyBuffer(1).add(Instant.parse("2020-03-09T14:53:24.518Z")));

			assertThat(bangkok).isEqualTo(utc);
		} finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void nullsAreCopysSentinelAndFieldsAreTabSeparated() {
		String rendered = render(new CopyBuffer(1).add("a").add(null).add(1L).endRow());

		assertThat(rendered).isEqualTo("a\t\\N\t1\n");
	}

	/** Backslashes and terminators in a JSON payload must never break the framing. */
	@Test
	void escapesBackslashesAndTerminators() {
		String rendered = render(new CopyBuffer(1).add("{\"a\":\"b\\\"c\"}\tx\ny"));

		assertThat(rendered).isEqualTo("{\"a\":\"b\\\\\"c\"}\\tx\\ny");
	}

	private static String render(CopyBuffer buffer) {
		return buffer.payload();
	}
}
