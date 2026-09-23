package com.stucray.raptor.betfair;

import com.stucray.raptor.recorder.StreamSource;
import com.stucray.raptor.recorder.StreamSourceFactory;
import com.stucray.raptor.scope.CaptureScope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a live stream for each connection attempt, and carries the resume
 * point between them.
 *
 * <p>Two things outlive a connection and so live here rather than in the source.
 * The first is {@code initialClk}/{@code clk}: a reconnect that resumes from
 * them costs Betfair a delta and costs us nothing, where a reconnect without
 * them re-images the whole book and loses whatever happened in between. The
 * second is the waiting — a Tuesday at 03:00 has nothing in scope, and opening a
 * connection to subscribe to no markets would mean a socket with no heartbeat,
 * which the watchdog would correctly tear down every thirty seconds forever.
 *
 * <p>Not a {@code @Component}: it is created by {@link BetfairConfiguration}
 * only when this process has both a live stream switched on and credentials to
 * open one with. Absent, the supervisor takes no lease and records nothing, and
 * says so — which is the correct behaviour of a developer's laptop and of every
 * instance that is not the one capturing.
 */
final class TlsStreamSourceFactory implements StreamSourceFactory {

	private static final Logger log = LoggerFactory.getLogger(TlsStreamSourceFactory.class);

	/** How often to look for something to capture while scope is empty. */
	private static final Duration IDLE_POLL = Duration.ofSeconds(30);

	private final BetfairSession session;
	private final BetfairProperties betfair;
	private final StreamProperties properties;
	private final CaptureScope scope;
	private final Clock clock;

	/**
	 * When the wait for scope began, or {@code null} while not waiting.
	 *
	 * <p>Read by the supervisor to tell "idle by design" from "cannot reach
	 * Betfair" — two conditions that used to share the word RECONNECTING (#144).
	 * The instant and not a flag, because a bare word cannot answer "is this
	 * normal?": four minutes of it on a matchday evening is not the same news as
	 * four hours of it overnight.
	 */
	private volatile @Nullable Instant idleSince;

	/** The source in flight, so the next one can resume where it left off. */
	private volatile @Nullable TlsStreamSource last;

	TlsStreamSourceFactory(BetfairSession session, BetfairProperties betfair,
			StreamProperties properties, CaptureScope scope, Clock clock) {
		this.session = session;
		this.betfair = betfair;
		this.properties = properties;
		this.scope = scope;
		this.clock = clock;
	}

	@Override
	public String describe() {
		return "Betfair Exchange Stream at " + properties.host() + ":" + properties.port();
	}

	@Override
	public @Nullable Instant awaitingSince() {
		return idleSince;
	}

	@Override
	public StreamSource open() throws IOException {
		awaitScope();
		TlsStreamSource previous = last;
		SSLSocket socket = connect();
		TlsStreamSource source = new TlsStreamSource(
				new SocketConnection(socket, properties.host() + ":" + properties.port()),
				session, betfair, properties, scope, clock,
				previous == null ? TlsStreamSource.Resume.NONE : previous.resume());
		boolean opened = false;
		try {
			source.open();
			opened = true;
		} finally {
			if (!opened) {
				// The resume point of a connection that never authenticated belongs to
				// the connection before it, not to this one — and a socket left open
				// after a failed handshake is a file descriptor leaked once per
				// reconnect, which on a five-second retry is a lot of them.
				closeQuietly(source);
			}
		}
		last = source;
		return source;
	}

	/**
	 * Wait until there is something to subscribe to.
	 *
	 * <p>On the supervisor's thread, deliberately: that thread exists to wait, and
	 * everything that must not wait is inside the recording it starts. Said once
	 * per idle period rather than per attempt — a log line every thirty seconds
	 * through a Tuesday is how a log stops being read.
	 */
	private void awaitScope() throws IOException {
		while (scope.plan().marketIds().isEmpty()) {
			if (idleSince == null) {
				idleSince = clock.instant();
				log.info("nothing in scope; the stream opens when a fixture enters the horizon");
			}
			try {
				Thread.sleep(IDLE_POLL);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while waiting for a fixture", e);
			}
		}
		idleSince = null;
	}

	private SSLSocket connect() throws IOException {
		SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
		try {
			socket.connect(new InetSocketAddress(properties.host(), properties.port()),
					(int) properties.connectTimeout().toMillis());
			socket.setSoTimeout((int) properties.readTimeout().toMillis());
			// Send a message the moment it is built. Nagle would hold a small
			// subscribe waiting for more bytes that are not coming.
			socket.setTcpNoDelay(true);
			socket.setKeepAlive(true);
			// Hostname verification is NOT on by default for a raw SSLSocket — it is
			// HttpsURLConnection and the HTTP clients that add it. Without this line
			// any certificate signed by any trusted CA is accepted for this host,
			// which is the whole of what TLS was doing for us here.
			SSLParameters parameters = socket.getSSLParameters();
			parameters.setEndpointIdentificationAlgorithm("HTTPS");
			socket.setSSLParameters(parameters);
			socket.startHandshake();
			return socket;
		} catch (IOException e) {
			try {
				socket.close();
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	private static void closeQuietly(TlsStreamSource source) {
		try {
			source.close();
		} catch (IOException e) {
			log.debug("a stream that failed to open did not close cleanly", e);
		}
	}
}
