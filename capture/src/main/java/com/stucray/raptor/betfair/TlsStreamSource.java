package com.stucray.raptor.betfair;

import com.stucray.raptor.recorder.StreamFrame;
import com.stucray.raptor.recorder.StreamSource;
import com.stucray.raptor.scope.CaptureScope;
import com.stucray.raptor.scope.SubscriptionPlan;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The live stream: authenticate, subscribe, hand every {@code mcm} to the write
 * path.
 *
 * <p>One connection, one instance. A source that has ended is thrown away and
 * another built, because the alternative — a source that can reconnect inside
 * itself — makes the disconnect invisible to the supervisor, and a disconnect
 * nobody wrote down is a gap somebody has to infer later from timestamp
 * arithmetic. That inference is the apparatus this whole design replaces.
 *
 * <p><b>Scope is never read on the read loop.</b> The market list moves as
 * fixtures enter and leave the horizon, and asking scope what to subscribe to
 * means a query against Postgres — which is the one thing the socket-draining
 * thread may never wait on. So the plan is re-read on a thread of its own and
 * the re-subscribe is sent from there; the read loop only ever reads. Getting
 * this wrong would be silent and would look like nothing at all until a
 * database stall coincided with a match, at which point the receive buffer
 * fills, the TCP window closes and Betfair drops us as a slow consumer.
 *
 * <p><b>Control messages are handled here and never handed on.</b> The framer
 * downstream counts a frame with no {@code pt} as unaddressable, which is
 * exactly the right thing for a torn {@code mcm} and exactly the wrong thing for
 * a status message — so the two are separated at the only place that can tell
 * them apart. Heartbeats <em>are</em> handed on: they carry a {@code pt} and no
 * {@code mc}, the framer produces no rows from them, and the read loop stamps
 * them as evidence the stream is alive. That is what a heartbeat is for.
 */
final class TlsStreamSource implements StreamSource {

	private static final Logger log = LoggerFactory.getLogger(TlsStreamSource.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** How long the maintainer sleeps in one go, so close() is not waited on. */
	private static final Duration SLICE = Duration.ofSeconds(1);

	private final StreamConnection connection;
	private final BetfairSession session;
	private final BetfairProperties betfair;
	private final StreamProperties properties;
	private final CaptureScope scope;
	private final Clock clock;

	private final AtomicLong messageIds = new AtomicLong();

	/** Anything read before the authentication status arrived, in arrival order. */
	private final Deque<String> pending = new ArrayDeque<>();

	private volatile @Nullable String initialClk;
	private volatile @Nullable String clk;
	private volatile List<String> subscribed = List.of();
	private volatile boolean closed;
	private volatile @Nullable Thread maintainer;

	TlsStreamSource(StreamConnection connection, BetfairSession session,
			BetfairProperties betfair, StreamProperties properties, CaptureScope scope,
			Clock clock, Resume resume) {
		this.connection = connection;
		this.session = session;
		this.betfair = betfair;
		this.properties = properties;
		this.scope = scope;
		this.clock = clock;
		this.initialClk = resume.initialClk();
		this.clk = resume.clk();
	}

	/**
	 * Where a reconnect picks up from.
	 *
	 * <p>Betfair replays from a {@code clk} for a bounded window — minutes, not
	 * tens of them. Resuming is therefore worth doing and worth not trusting: it
	 * turns a short disconnect into no loss at all, and past its window the
	 * intervening book is gone whatever we send. What makes the loss survivable is
	 * that it is <em>recorded</em>, in {@code raw.capture_gap}, rather than left
	 * to be noticed later.
	 */
	record Resume(@Nullable String initialClk, @Nullable String clk) {

		static final Resume NONE = new Resume(null, null);

		boolean resumable() {
			return initialClk != null;
		}
	}

	/**
	 * Log in, subscribe, and start following scope.
	 *
	 * @throws IOException if the connection fails, or Betfair refuses the session
	 *     — both of which the supervisor answers by waiting and building another
	 */
	void open() throws IOException {
		long authId = send(Map.of("op", "authentication",
				"appKey", betfair.appKey(),
				"session", session.token()));
		awaitAuthentication(authId);
		subscribe(scope.plan(), new Resume(initialClk, clk));
		maintainer = Thread.ofVirtual().name("betfair-subscription").start(this::maintain);
	}

	/**
	 * Read until the authentication is answered.
	 *
	 * <p>Betfair opens with a {@code connection} message and answers each request
	 * by id, so anything else that arrives in between is real and is kept — the
	 * queue exists so that a message which arrived before we were ready is not
	 * quietly dropped, which is the sort of loss that shows up as one missing
	 * market change months later.
	 */
	private void awaitAuthentication(long authId) throws IOException {
		while (true) {
			String line = connection.readLine();
			if (line == null) {
				throw new IOException("Betfair closed the connection before authenticating");
			}
			JsonNode message = parse(line);
			if (message == null) {
				continue;
			}
			String op = text(message, "op");
			if ("status".equals(op)) {
				checkStatus(message);
				JsonNode id = message.get("id");
				if (id == null || id.asLong() == authId) {
					log.info("Betfair stream authenticated ({})", connection.describe());
					return;
				}
				continue;
			}
			if ("connection".equals(op)) {
				log.info("Betfair stream connection {}", text(message, "connectionId"));
				continue;
			}
			pending.addLast(line);
		}
	}

	@Override
	public String describe() {
		return "Betfair stream at " + connection.describe();
	}

	@Override
	public @Nullable StreamFrame next() throws IOException {
		while (true) {
			String line = pending.pollFirst();
			if (line == null) {
				line = connection.readLine();
			}
			if (line == null) {
				log.info("Betfair closed the stream; the supervisor will rebuild it");
				return null;
			}
			JsonNode message = parse(line);
			if (message == null) {
				// Not JSON. Nothing can be done with it, and one torn frame says
				// nothing about the next: the framer downstream takes the same view
				// for the same reason.
				continue;
			}
			String op = text(message, "op");
			if ("mcm".equals(op)) {
				remember(message);
				return new StreamFrame(line, clock.instant());
			}
			if ("status".equals(op)) {
				checkStatus(message);
				continue;
			}
			if ("connection".equals(op)) {
				log.info("Betfair stream connection {}", text(message, "connectionId"));
			}
		}
	}

	/**
	 * Betfair's own account of a request, and what each refusal costs.
	 *
	 * <p>Every failure ends the stream, because there is nothing useful to do with
	 * a connection whose subscription was refused. What differs is what is said
	 * and what is thrown away on the way out.
	 */
	private void checkStatus(JsonNode message) throws IOException {
		if (!"FAILURE".equals(text(message, "statusCode"))) {
			if (message.get("connectionClosed") != null
					&& message.get("connectionClosed").asBoolean(false)) {
				throw new IOException("Betfair closed the connection");
			}
			return;
		}
		String code = text(message, "errorCode");
		if (BetfairException.sessionLapsed(code)) {
			// The token, not the request. Dropping it here is what makes the
			// reconnect a different attempt rather than the same one again — the
			// failure mode that cost the whole of 2026-09-01's window.
			session.invalidate();
			throw new IOException("Betfair refused the session (" + code + "); "
					+ "the next connection will log in again");
		}
		if ("SUBSCRIPTION_LIMIT_EXCEEDED".equals(code)) {
			// Not transient, and the Python exits on it. A resident service cannot:
			// exiting is what the launchd era did instead of recovering, and the
			// planner trims to the cap on every pass, so the next attempt may
			// legitimately be smaller. It is said at ERROR because a configuration
			// asking for more than one connection can carry will otherwise reconnect
			// quietly forever.
			log.error("Betfair refused the subscription of {} markets as too large; "
					+ "the market-type list or the horizon is asking for more than one "
					+ "connection can carry", subscribed.size());
			throw new IOException("subscription refused: " + code);
		}
		throw new IOException("Betfair stream failure: " + code);
	}

	/** Keep the resume point current. */
	private void remember(JsonNode message) {
		String freshInitial = text(message, "initialClk");
		if (!freshInitial.isEmpty()) {
			initialClk = freshInitial;
		}
		String freshClk = text(message, "clk");
		if (!freshClk.isEmpty()) {
			clk = freshClk;
		}
	}

	/** What the next connection should resume from. */
	Resume resume() {
		return new Resume(initialClk, clk);
	}

	/** The markets in the subscription Betfair last accepted. */
	List<String> subscribed() {
		return subscribed;
	}

	private void maintain() {
		Duration interval = properties.resubscribeInterval();
		while (!closed) {
			if (!sleep(interval)) {
				return;
			}
			try {
				follow();
			} catch (IOException e) {
				// The socket is gone. The read loop is about to find that out for
				// itself and the supervisor rebuilds from there; saying it twice is
				// noise, so this is the quieter of the two reports.
				log.debug("could not re-subscribe: {}", e.toString());
				return;
			} catch (RuntimeException e) {
				// Scope lives in Postgres. A failed read leaves the subscription
				// exactly as it is, which is the safe direction: a market subscribed
				// a little too long costs storage, and a market dropped because a
				// query timed out costs a match.
				log.warn("could not read scope; the subscription is unchanged ({})", e.toString());
			}
		}
	}

	/**
	 * Re-subscribe if the market list has moved.
	 *
	 * <p>Package-private so a test can drive one pass without waiting for the
	 * interval.
	 */
	void follow() throws IOException {
		SubscriptionPlan plan = scope.plan();
		if (plan.marketIds().equals(subscribed)) {
			return;
		}
		if (plan.marketIds().isEmpty()) {
			// Everything in the last subscription has finished. Sending an empty
			// marketSubscription would replace a harmless subscription to closed
			// markets with a request Betfair has no good answer to; the markets
			// themselves have already stopped sending.
			log.info("nothing in scope; keeping the current subscription of {} market(s)",
					subscribed.size());
			return;
		}
		log.info("scope moved: re-subscribing to {} market(s)", plan.marketIds().size());
		// Without a clk, deliberately. The subscription is a different one, so the
		// server owes a full image of it; asking to resume a delta against the
		// previous market set is how a market ends up subscribed with no book
		// behind it.
		subscribe(plan, Resume.NONE);
	}

	private void subscribe(SubscriptionPlan plan, Resume from) throws IOException {
		Map<String, Object> subscription = new LinkedHashMap<>();
		subscription.put("op", "marketSubscription");
		subscription.put("marketFilter", Map.of("marketIds", plan.marketIds()));
		subscription.put("marketDataFilter", Map.of(
				"fields", properties.fields(),
				"conflateMs", properties.conflate().toMillis()));
		subscription.put("heartbeatMs", properties.heartbeat().toMillis());
		if (from.resumable()) {
			subscription.put("initialClk", from.initialClk());
			subscription.put("clk", from.clk());
		}
		send(subscription);
		subscribed = plan.marketIds();
		// After the send, never before: a market recorded as subscribed that the
		// server refused would be protected from the next trim by the very tier
		// that exists to protect markets actually being recorded.
		scope.subscribed(plan.marketIds());
		if (plan.droppedRequestedEvents() > 0) {
			log.warn("{} requested event(s) did not fit in the subscription",
					plan.droppedRequestedEvents());
		}
	}

	private long send(Map<String, Object> message) throws IOException {
		long id = messageIds.incrementAndGet();
		Map<String, Object> withId = new LinkedHashMap<>(message);
		withId.put("id", id);
		connection.send(MAPPER.writeValueAsString(withId));
		return id;
	}

	private static @Nullable JsonNode parse(String line) {
		try {
			return MAPPER.readTree(line);
		} catch (JacksonException e) {
			return null;
		}
	}

	private static String text(JsonNode message, String field) {
		JsonNode value = message.get(field);
		return value == null || value.isNull() ? "" : value.asString();
	}

	/** @return false if the wait was cut short by a close */
	private boolean sleep(Duration total) {
		Duration remaining = total;
		while (!remaining.isNegative() && !remaining.isZero()) {
			if (closed) {
				return false;
			}
			Duration slice = remaining.compareTo(SLICE) < 0 ? remaining : SLICE;
			try {
				Thread.sleep(slice);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			remaining = remaining.minus(slice);
		}
		return !closed;
	}

	@Override
	public void close() throws IOException {
		closed = true;
		Thread thread = maintainer;
		if (thread != null) {
			thread.interrupt();
			maintainer = null;
		}
		connection.close();
	}
}
