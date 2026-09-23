package com.stucray.raptor.betfair;

/**
 * A Betfair call that failed, carrying Betfair's own account of why.
 *
 * <p>The reason this type exists rather than an {@code IOException}: the
 * response body is the only place the upstream puts its fault code, and
 * discarding it turns a lapsed session into {@code HTTP 400 Bad Request} and a
 * stack trace. That exact loss cost a whole capture night on 2026-09-01 in the
 * Python recorder, where the log said "Bad Request" for four seconds of work
 * and twelve hours of silence.
 */
public class BetfairException extends RuntimeException {

	private final String faultCode;

	BetfairException(String message, String faultCode) {
		super(message);
		this.faultCode = faultCode;
	}

	BetfairException(String message, Throwable cause) {
		super(message, cause);
		this.faultCode = "";
	}

	/** Betfair's own code — {@code INVALID_SESSION_INFORMATION} and friends. */
	public String faultCode() {
		return faultCode;
	}

	/** Whether the session, rather than the request, is what Betfair objected to. */
	public boolean sessionLapsed() {
		return sessionLapsed(faultCode);
	}

	/**
	 * The same question asked of a bare code.
	 *
	 * <p>The stream reports its refusals as a {@code status} message rather than
	 * as an HTTP response, so it has a code and no exception. One list, asked two
	 * ways: a session fault that the REST client recognised and the stream did not
	 * would mean the recorder reconnecting forever with a token it already knows
	 * is dead.
	 */
	static boolean sessionLapsed(String faultCode) {
		return "INVALID_SESSION_INFORMATION".equals(faultCode)
				|| "NO_SESSION".equals(faultCode)
				|| "NOT_AUTHORIZED".equals(faultCode)
				|| "INVALID_SESSION_TOKEN".equals(faultCode);
	}
}
