package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import com.stucray.raptor.recorder.StreamFrame;
import com.stucray.raptor.scope.CaptureScope;
import com.stucray.raptor.scope.SubscriptionPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The stream protocol, without a stream.
 *
 * <p>Everything Betfair-shaped about this source is a decision — what to send,
 * what to hand on, what to do with a refusal — and every one of those decisions
 * is settled here against scripted lines. What is deliberately <em>not</em>
 * asserted here is that Betfair behaves as scripted: that is
 * {@link BetfairStreamLiveSmokeTest}'s job, because a mock that agrees with the
 * code is how this module's first four wire facts went unnoticed until a live
 * run.
 */
class TlsStreamSourceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Instant NOW = Instant.parse("2026-09-02T19:30:00Z");

	private static final String CONNECTED =
			"{\"op\":\"connection\",\"connectionId\":\"002-000000000000-000000\"}";
	private static final String AUTHENTICATED =
			"{\"op\":\"status\",\"id\":1,\"statusCode\":\"SUCCESS\",\"connectionClosed\":false}";

	private final FakeConnection connection = new FakeConnection();
	private final FakeScope scope = new FakeScope();
	private final RecordingSession session = new RecordingSession();

	@Test
	void authenticatesAndThenSubscribesToWhatScopeAsksFor() throws IOException {
		connection.deliver(CONNECTED, AUTHENTICATED);
		scope.plan = plan("1.240", "1.241");

		open();

		Map<String, Object> authentication = connection.sent(0);
		assertThat(authentication).containsEntry("op", "authentication")
				.containsEntry("appKey", "app-key").containsEntry("session", "token-1")
				.containsEntry("id", 1);
		Map<String, Object> subscription = connection.sent(1);
		assertThat(subscription).containsEntry("op", "marketSubscription")
				.containsEntry("marketFilter", Map.of("marketIds", List.of("1.240", "1.241")))
				.containsEntry("heartbeatMs", 5000);
		assertThat(subscription.get("marketDataFilter")).isEqualTo(Map.of(
				"fields", List.of("EX_MARKET_DEF", "EX_ALL_OFFERS", "EX_TRADED", "EX_TRADED_VOL",
						"EX_LTP"),
				"conflateMs", 0));
		// After the send, and only for the markets that went into it: a market
		// recorded as subscribed that the server never saw would be protected from
		// the next trim by the tier that exists to protect live ones.
		assertThat(scope.subscribed).containsExactly(List.of("1.240", "1.241"));
	}

	@Test
	void resumesFromTheClockOfTheConnectionBefore() throws IOException {
		connection.deliver(AUTHENTICATED);
		scope.plan = plan("1.240");

		source(new TlsStreamSource.Resume("initial-1", "clk-9")).open();

		assertThat(connection.sent(1)).containsEntry("initialClk", "initial-1")
				.containsEntry("clk", "clk-9");
	}

	@Test
	void handsOnMarketChangesVerbatimAndKeepsControlMessagesToItself() throws IOException {
		String mcm = "{\"op\":\"mcm\",\"id\":2,\"clk\":\"AAA\",\"pt\":1756838000000,"
				+ "\"mc\":[{\"id\":\"1.240\",\"rc\":[{\"ltp\":2.5,\"id\":47999}]}]}";
		String heartbeat = "{\"op\":\"mcm\",\"id\":2,\"ct\":\"HEARTBEAT\",\"clk\":\"AAB\","
				+ "\"pt\":1756838005000}";
		connection.deliver(CONNECTED, AUTHENTICATED, mcm, CONNECTED, heartbeat);
		TlsStreamSource source = open();

		// The market change, byte for byte as it arrived: nothing on this path may
		// depend on a parse, so the frame handed on is the line and not a
		// re-serialisation of it.
		StreamFrame first = source.next();
		assertThat(first).isNotNull();
		assertThat(first.json()).isEqualTo(mcm);
		assertThat(first.receivedAt()).isEqualTo(NOW);

		// The second connection message is skipped, not handed on: the framer
		// downstream would count it as a block with no pt, which is a real finding
		// about a torn mcm and a lie about a control message.
		StreamFrame second = source.next();
		assertThat(second).isNotNull();
		assertThat(second.json()).isEqualTo(heartbeat);

		// A heartbeat IS handed on. It produces no rows, and the read loop stamps
		// it as evidence the stream is alive — which is the whole reason Betfair
		// sends one.
		assertThat(source.next()).isNull();
	}

	@Test
	void remembersWhereToResumeFrom() throws IOException {
		connection.deliver(AUTHENTICATED,
				"{\"op\":\"mcm\",\"initialClk\":\"i-1\",\"clk\":\"c-1\",\"pt\":1,\"mc\":[]}",
				"{\"op\":\"mcm\",\"clk\":\"c-2\",\"pt\":2,\"mc\":[]}");
		TlsStreamSource source = open();
		source.next();
		source.next();

		// clk moves with every message; initialClk is set once by the image and
		// then kept — a resume needs both, and the second message carries only one.
		assertThat(source.resume()).isEqualTo(new TlsStreamSource.Resume("i-1", "c-2"));
	}

	@Test
	void dropsTheSessionWhenBetfairRefusesIt() throws IOException {
		connection.deliver(AUTHENTICATED, "{\"op\":\"status\",\"statusCode\":\"FAILURE\","
				+ "\"errorCode\":\"INVALID_SESSION_INFORMATION\"}");
		TlsStreamSource source = open();

		assertThatIOException().isThrownBy(source::next)
				.withMessageContaining("INVALID_SESSION_INFORMATION");
		// The token is the problem, so it is thrown away here. Without this the
		// supervisor reconnects every five seconds with a session it already knows
		// is dead — the shape that cost the whole of 2026-09-01's window.
		assertThat(session.invalidated).isTrue();
	}

	@Test
	void refusesToAuthenticateWithNoCredentials() {
		connection.deliver("{\"op\":\"status\",\"id\":1,\"statusCode\":\"FAILURE\","
				+ "\"errorCode\":\"NO_APP_KEY\"}");

		assertThatIOException().isThrownBy(() -> open()).withMessageContaining("NO_APP_KEY");
	}

	@Test
	void endsTheStreamWhenTheSubscriptionIsTooLarge() throws IOException {
		connection.deliver(AUTHENTICATED, "{\"op\":\"status\",\"statusCode\":\"FAILURE\","
				+ "\"errorCode\":\"SUBSCRIPTION_LIMIT_EXCEEDED\"}");
		TlsStreamSource source = open();

		// The Python exits the process on this one, on the grounds that it is a
		// configuration error rather than a transient fault. A resident service
		// cannot: it ends the stream, says so at ERROR, and lets the planner —
		// which trims to the cap on every pass — produce a smaller one.
		assertThatIOException().isThrownBy(source::next)
				.withMessageContaining("SUBSCRIPTION_LIMIT_EXCEEDED");
	}

	@Test
	void resubscribesWithNoClockWhenScopeMoves() throws IOException {
		connection.deliver(AUTHENTICATED);
		scope.plan = plan("1.240");
		TlsStreamSource source = source(new TlsStreamSource.Resume("i-1", "c-1"));
		source.open();

		scope.plan = plan("1.240", "1.999");
		source.follow();

		Map<String, Object> resubscription = connection.sent(2);
		assertThat(resubscription).containsEntry("marketFilter",
				Map.of("marketIds", List.of("1.240", "1.999")));
		// Deliberately no clk. The subscription is a different one, so the server
		// owes a full image of it; resuming a delta that belongs to the previous
		// market set subscribes to a market with no book behind it.
		assertThat(resubscription).doesNotContainKey("clk").doesNotContainKey("initialClk");
		assertThat(scope.subscribed).containsExactly(List.of("1.240"), List.of("1.240", "1.999"));
	}

	@Test
	void saysNothingToBetfairWhenScopeHasNotMoved() throws IOException {
		connection.deliver(AUTHENTICATED);
		scope.plan = plan("1.240");
		TlsStreamSource source = open();

		source.follow();

		assertThat(connection.sends).hasSize(2);
	}

	@Test
	void keepsTheLastSubscriptionWhenScopeEmpties() throws IOException {
		connection.deliver(AUTHENTICATED);
		scope.plan = plan("1.240");
		TlsStreamSource source = open();

		scope.plan = plan();
		source.follow();

		// An empty marketSubscription replaces a harmless subscription to markets
		// that have finished with a request Betfair has no good answer to. The
		// markets themselves have already stopped sending.
		assertThat(connection.sends).hasSize(2);
	}

	private TlsStreamSource open() throws IOException {
		TlsStreamSource source = source(TlsStreamSource.Resume.NONE);
		source.open();
		return source;
	}

	private TlsStreamSource source(TlsStreamSource.Resume resume) {
		return new TlsStreamSource(connection, session, properties(), streamProperties(), scope,
				Clock.fixed(NOW, ZoneOffset.UTC), resume);
	}

	private static SubscriptionPlan plan(String... marketIds) {
		return new SubscriptionPlan(List.of(marketIds), 0, 0);
	}

	private static BetfairProperties properties() {
		return new BetfairProperties("app-key", "user", "password", "", "",
				"https://identitysso-cert.betfair.com/api/certlogin",
				"https://identitysso.betfair.com/api/keepAlive",
				"https://api.betfair.com/exchange/betting/rest/v1.0/",
				Path.of("../config/capture.properties"));
	}

	private static StreamProperties streamProperties() {
		return new StreamProperties(true, "stream-api.betfair.com", 443, Duration.ofSeconds(30),
				Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO,
				List.of("EX_MARKET_DEF", "EX_ALL_OFFERS", "EX_TRADED", "EX_TRADED_VOL", "EX_LTP"),
				Duration.ofSeconds(60));
	}

	/** A session that answers without logging in, and remembers being dropped. */
	private static final class RecordingSession extends BetfairSession {

		private boolean invalidated;

		private RecordingSession() {
			super(properties(), RestClient.builder());
		}

		@Override
		String token() {
			return "token-1";
		}

		@Override
		void invalidate() {
			invalidated = true;
		}
	}

	/** Scope, scripted: whatever the test says is in it. */
	private static final class FakeScope implements CaptureScope {

		private SubscriptionPlan plan = new SubscriptionPlan(List.of(), 0, 0);
		private final List<List<String>> subscribed = new ArrayList<>();

		@Override
		public SubscriptionPlan plan() {
			return plan;
		}

		@Override
		public void subscribed(List<String> marketIds) {
			subscribed.add(List.copyOf(marketIds));
		}
	}

	/** Lines in, lines out. The socket, with the socket taken out. */
	private static final class FakeConnection implements StreamConnection {

		private final Deque<String> incoming = new ArrayDeque<>();
		private final List<String> sends = new ArrayList<>();

		void deliver(String... lines) {
			incoming.addAll(List.of(lines));
		}

		Map<String, Object> sent(int index) {
			return MAPPER.readValue(sends.get(index), new TypeReference<Map<String, Object>>() {});
		}

		@Override
		public String describe() {
			return "a scripted connection";
		}

		@Override
		public void send(String json) {
			sends.add(json);
		}

		@Override
		public @Nullable String readLine() {
			return incoming.pollFirst();
		}

		@Override
		public void close() {
			incoming.clear();
		}
	}
}
