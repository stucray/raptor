package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The line protocol, over a real socket on the loopback.
 *
 * <p>A real socket rather than a pair of streams, because the behaviour worth
 * proving is the one a stream cannot show: bytes arrive in whatever chunks the
 * network hands over, and a line is only a line once its newline has arrived.
 * The reader S4 replaced had exactly this bug in the other direction — it waited
 * for its buffer to fill before returning anything — and on a socket that would
 * hold a burst of market changes until minutes of a quiet market had produced
 * eight more kilobytes.
 */
class SocketConnectionTest {

	@Test
	void deliversALineAsSoonAsItsNewlineArrives() throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
					Socket accepted = server.accept();
					SocketConnection connection = new SocketConnection(client, "loopback")) {
				OutputStream peer = accepted.getOutputStream();

				// Half a message, then the rest of it and the whole of the next: no
				// alignment between what is written and what is read.
				write(peer, "{\"op\":\"conn");
				write(peer, "ection\"}\r\n{\"op\":\"status\"}\r\n");

				assertThat(connection.readLine()).isEqualTo("{\"op\":\"connection\"}");
				assertThat(connection.readLine()).isEqualTo("{\"op\":\"status\"}");

				// A multi-byte character split across two reads survives, because the
				// split is done on bytes and the decode afterwards. Team names are
				// full of them.
				write(peer, "{\"name\":\"Bay");
				write(peer, new byte[] {(byte) 0xC3});
				write(peer, new byte[] {(byte) 0xA9, 'r', 'n'});
				write(peer, "\"}\n");
				assertThat(connection.readLine()).isEqualTo("{\"name\":\"Bayérn\"}");

				accepted.close();
				assertThat(connection.readLine()).isNull();
			}
		}
	}

	@Test
	void sendsWhatBetfairExpectsToRead() throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
					Socket accepted = server.accept();
					SocketConnection connection = new SocketConnection(client, "loopback")) {
				connection.send("{\"op\":\"heartbeat\"}");

				byte[] read = accepted.getInputStream().readNBytes(20);
				// CRLF, the documented terminator. The server tolerates a bare
				// newline, which is exactly the kind of tolerance that stops being
				// true on a Saturday.
				assertThat(new String(read, StandardCharsets.UTF_8))
						.isEqualTo("{\"op\":\"heartbeat\"}\r\n");
			}
		}
	}

	private static void write(OutputStream out, String text) throws IOException {
		write(out, text.getBytes(StandardCharsets.UTF_8));
	}

	private static void write(OutputStream out, byte[] bytes) throws IOException {
		out.write(bytes);
		out.flush();
	}
}
