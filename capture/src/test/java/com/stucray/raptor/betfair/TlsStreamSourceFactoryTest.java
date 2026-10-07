package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.recorder.StreamSource;
import com.stucray.raptor.scope.CaptureScope;
import com.stucray.raptor.scope.ConnectionSubscription;
import com.stucray.raptor.scope.SubscriptionPlan;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Log in first, then open the stream (#12).
 *
 * <p>On 2026-09-25 a stream attempt connected, then asked for a token, and the
 * certificate login took four minutes on a failing network. Betfair closed the
 * unauthenticated connection ({@code TIMEOUT}) five milliseconds after the
 * login returned. Every second spent logging in was spent on Betfair's clock.
 */
class TlsStreamSourceFactoryTest {

	private static final String AUTHENTICATED =
			"{\"op\":\"status\",\"id\":1,\"statusCode\":\"SUCCESS\",\"connectionClosed\":false}";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * A login that fails costs an attempt, not a connection — and is reported as
	 * an attempt that failed.
	 *
	 * <p>Two things at once. The listener must see no connection, because the
	 * token is needed before the socket is worth opening. And the failure must
	 * reach the supervisor as an {@link IOException}: the supervisor retries
	 * those, and a {@link BetfairException} is a {@code RuntimeException} that
	 * its loop does not catch.
	 */
	@Test
	@Timeout(10)
	void aLoginThatFailsNeverOpensTheSocketAndIsAnOrdinaryFailedAttempt() throws Exception {
		AtomicInteger accepted = new AtomicInteger();
		try (ServerSocket listener = new ServerSocket(0)) {
			Thread.ofVirtual().start(() -> {
				while (!listener.isClosed()) {
					try (Socket ignored = listener.accept()) {
						accepted.incrementAndGet();
					} catch (IOException closed) {
						return;
					}
				}
			});
			TlsStreamSourceFactory factory = new TlsStreamSourceFactory(
					noCredentials(), betfair(""), stream(listener.getLocalPort()),
					inScope(), Clock.systemUTC());

			assertThatThrownBy(() -> factory.open(0))
					.isInstanceOf(IOException.class)
					.hasCauseInstanceOf(BetfairException.class);
			assertThat(accepted).hasValue(0);
		}
	}

	/**
	 * Each slot resumes from its own clock, and a finished slot from none (#65).
	 *
	 * <p>A clock is a position in one connection's market set. Handed to another
	 * slot it would resume a delta against markets that connection never
	 * subscribed; kept by a slot that closed for want of anything to carry, it
	 * would resume the old set against a new one.
	 */
	@Test
	@Timeout(10)
	void eachSlotResumesFromItsOwnClockAndAFinishedOneFromNone() throws Exception {
		Deque<ScriptedConnection> connections = new ArrayDeque<>();
		MutablePlan scope = new MutablePlan(List.of("1.1"), List.of("1.2"));
		TlsStreamSourceFactory factory = new TlsStreamSourceFactory(signedIn(), betfair("key"),
				stream(443), scope, Clock.systemUTC(), connections::removeFirst);

		connections.add(new ScriptedConnection(AUTHENTICATED, change("i-0", "c-0")));
		connections.add(new ScriptedConnection(AUTHENTICATED, change("i-1", "c-1")));
		readOne(factory.open(0));
		readOne(factory.open(1));

		ScriptedConnection reopenedOne = new ScriptedConnection(AUTHENTICATED);
		ScriptedConnection reopenedZero = new ScriptedConnection(AUTHENTICATED);
		connections.add(reopenedOne);
		connections.add(reopenedZero);
		factory.open(1);
		factory.open(0);

		assertThat(reopenedOne.subscription()).containsEntry("initialClk", "i-1")
				.containsEntry("clk", "c-1");
		assertThat(reopenedZero.subscription()).containsEntry("initialClk", "i-0")
				.containsEntry("clk", "c-0");

		// Slot 1's plan empties, so its connection finishes; when it is given a
		// fixture again it is a new market set, owed a full image.
		connections.add(new ScriptedConnection(AUTHENTICATED));
		TlsStreamSource second = (TlsStreamSource) factory.open(1);
		scope.slotOne = List.of();
		second.follow();
		assertThat(second.finished()).isTrue();
		scope.slotOne = List.of("1.3");
		ScriptedConnection afterFinish = new ScriptedConnection(AUTHENTICATED);
		connections.add(afterFinish);
		factory.open(1);

		assertThat(afterFinish.subscription()).doesNotContainKey("clk")
				.doesNotContainKey("initialClk");
	}

	private static void readOne(StreamSource source)
			throws IOException {
		assertThat(source.next()).as("the scripted market change").isNotNull();
	}

	private static String change(String initialClk, String clk) {
		return "{\"op\":\"mcm\",\"id\":2,\"initialClk\":\"" + initialClk + "\",\"clk\":\""
				+ clk + "\",\"pt\":1,\"mc\":[]}";
	}

	/** A session that answers without logging in. */
	private static BetfairSession signedIn() {
		return new BetfairSession(betfair("key"), RestClient.builder()) {
			@Override
			String token() {
				return "token";
			}
		};
	}

	/** A scope whose two slots the test moves between. */
	private static final class MutablePlan implements CaptureScope {

		private volatile List<String> slotZero;
		private volatile List<String> slotOne;

		MutablePlan(List<String> slotZero, List<String> slotOne) {
			this.slotZero = slotZero;
			this.slotOne = slotOne;
		}

		@Override
		public SubscriptionPlan plan() {
			return new SubscriptionPlan(List.of(new ConnectionSubscription(0, slotZero),
					new ConnectionSubscription(1, slotOne)), 0, 0);
		}

		@Override
		public void subscribed(int connectionSlot, List<String> marketIds) {
		}

		@Override
		public int connectionSlots() {
			return 2;
		}
	}

	/** One scripted connection: what it says, and what it was sent. */
	private static final class ScriptedConnection implements StreamConnection {

		private final Deque<String> incoming;
		private final List<String> sends = new ArrayList<>();

		ScriptedConnection(String... lines) {
			incoming = new ArrayDeque<>(List.of(lines));
		}

		Map<String, Object> subscription() {
			return MAPPER.readValue(sends.get(1), new TypeReference<Map<String, Object>>() {});
		}

		@Override
		public String describe() {
			return "scripted";
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

	private static BetfairSession noCredentials() {
		return new BetfairSession(betfair(""), RestClient.builder());
	}

	private static BetfairProperties betfair(String appKey) {
		return new BetfairProperties(appKey, "", "", "", "", "https://certlogin.invalid",
				"https://keepalive.invalid", "https://rest.invalid/",
				Path.of("config/capture.properties"));
	}

	private static StreamProperties stream(int port) {
		return new StreamProperties(true, "127.0.0.1", port, Duration.ofSeconds(1),
				Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ZERO,
				List.of("EX_MARKET_DEF"), Duration.ofSeconds(60));
	}

	private static CaptureScope inScope() {
		return new CaptureScope() {
			@Override
			public SubscriptionPlan plan() {
				return SubscriptionPlan.onSlotZero(List.of("1.900000001"));
			}

			@Override
			public void subscribed(int connectionSlot, List<String> marketIds) {
			}
		};
	}
}
