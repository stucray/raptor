package com.stucray.raptor.betfair;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Newline-delimited JSON over a socket.
 *
 * <p>Byte-level rather than a {@code BufferedReader}, for the reason S4 found
 * the hard way on the capture files: a buffered reader fills its buffer before
 * it returns a line, so a stream that has delivered half of what the buffer
 * wants simply waits. On a file that turned a truncated capture into zero
 * messages; on a socket it would hold every message of a burst until 8 KB more
 * arrived, which on a quiet market is minutes. Reading what is available and
 * splitting it here is the only shape that delivers a line as soon as the line
 * exists.
 *
 * <p>Lines are split on bytes and decoded afterwards, so a multi-byte character
 * straddling two reads cannot be mangled — team names are full of them.
 */
final class SocketConnection implements StreamConnection {

	/** One read's worth. The Python's is the same size, and it is not a limit. */
	private static final int CHUNK = 65536;

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final String describe;

	private byte[] buffer = new byte[0];

	SocketConnection(Socket socket, String describe) throws IOException {
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
		this.describe = describe;
	}

	@Override
	public String describe() {
		return describe;
	}

	/**
	 * Synchronized because two threads write: the subscription maintainer sends a
	 * re-subscribe while the read loop is reading, and a half-written message
	 * interleaved with another is a protocol error Betfair answers by closing the
	 * connection.
	 */
	@Override
	public synchronized void send(String json) throws IOException {
		// CRLF, as the Python has always sent. The documented terminator is \r\n
		// and the server is tolerant of \n, which is exactly the kind of tolerance
		// that stops being true on a Saturday.
		out.write((json + "\r\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	@Override
	public @Nullable String readLine() throws IOException {
		while (true) {
			int newline = indexOfNewline();
			if (newline >= 0) {
				String line = new String(buffer, 0, newline, StandardCharsets.UTF_8).strip();
				buffer = Arrays.copyOfRange(buffer, newline + 1, buffer.length);
				if (!line.isEmpty()) {
					return line;
				}
				continue;
			}
			byte[] chunk = new byte[CHUNK];
			int read = in.read(chunk);
			if (read < 0) {
				// The peer closed. Anything still in the buffer is half a message by
				// definition — there is no newline in it — and is dropped rather than
				// handed on as though it were whole.
				return null;
			}
			byte[] grown = Arrays.copyOf(buffer, buffer.length + read);
			System.arraycopy(chunk, 0, grown, buffer.length, read);
			buffer = grown;
		}
	}

	private int indexOfNewline() {
		for (int i = 0; i < buffer.length; i++) {
			if (buffer[i] == '\n') {
				return i;
			}
		}
		return -1;
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
