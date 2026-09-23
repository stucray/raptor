package com.stucray.raptor.betfair;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Exchange Stream connection, and what each dial costs.
 *
 * <p>Every default here is the value {@code record_suspensions.py} has been
 * running against Betfair for a season. That is the point of writing them down
 * rather than choosing fresh ones: the rate limits, the conflation behaviour and
 * the heartbeat cadence are all things the Python has already found the edges
 * of, and this slice is not the place to find them again with a live match on
 * the line.
 *
 * @param enabled whether this process may open a live stream at all. Off leaves
 *     the recorder with no source, which is the honest state of a developer's
 *     laptop and of any instance that is not the one capturing.
 * @param host Betfair's stream endpoint. Newline-delimited JSON over TLS —
 *     there is no websocket in this protocol, which is why a plain socket is the
 *     whole client.
 * @param connectTimeout how long to wait for the socket. Short: a connect that
 *     is going to fail should fail while there is still time to try again.
 * @param readTimeout how long a read may block before the socket is judged dead.
 *     Six heartbeats, so it can only fire after the watchdog has already had its
 *     say; it exists so that a socket which never returns cannot pin the read
 *     loop forever.
 * @param heartbeat how often Betfair should send one with no book to send. Five
 *     seconds is what makes a quiet market distinguishable from a dead socket,
 *     and the watchdog's 30 s silence timeout is six of these.
 * @param conflate the server-side conflation window. <b>Zero, deliberately:</b>
 *     every change, unconflated. The corpus exists to answer whether a bet could
 *     have been filled, and conflation deletes exactly the short suspensions
 *     that question turns on — the historic BASIC feed's one-minute conflation
 *     is why these captures had to be made at all.
 * @param fields the market data to request. The same five the Python asks for:
 *     market definition (the suspension clock), full depth both sides, traded
 *     volume per price and per runner, and last traded price so captures stay
 *     comparable with the BASIC corpus.
 * @param resubscribeInterval how often to ask scope whether the market list has
 *     moved. <b>Not on the read loop</b> — see {@link TlsStreamSource} — and so
 *     free to be far shorter than the catalogue poll it follows.
 */
@ConfigurationProperties("raptor.betfair.stream")
public record StreamProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("stream-api.betfair.com") String host,
		@DefaultValue("443") int port,
		@DefaultValue("30s") Duration connectTimeout,
		@DefaultValue("30s") Duration readTimeout,
		@DefaultValue("5s") Duration heartbeat,
		@DefaultValue("0s") Duration conflate,
		@DefaultValue({"EX_MARKET_DEF", "EX_ALL_OFFERS", "EX_TRADED", "EX_TRADED_VOL", "EX_LTP"})
		List<String> fields,
		@DefaultValue("60s") Duration resubscribeInterval) {

	public StreamProperties {
		fields = List.copyOf(fields);
		if (port < 1 || heartbeat.isNegative() || heartbeat.isZero()) {
			throw new IllegalArgumentException("the stream needs a port and a positive heartbeat");
		}
		if (resubscribeInterval.isNegative() || resubscribeInterval.isZero()) {
			throw new IllegalArgumentException("the re-subscribe interval must be positive");
		}
	}
}
