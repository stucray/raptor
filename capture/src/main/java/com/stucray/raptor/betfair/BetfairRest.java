package com.stucray.raptor.betfair;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Betfair's betting REST endpoints, with their refusals made legible.
 *
 * <p>The one behaviour worth the wrapper: <b>a non-2xx response body is read
 * and its fault code surfaced</b>. Betfair puts the actual reason
 * ({@code INVALID_SESSION_INFORMATION}, {@code TOO_MUCH_DATA},
 * {@code INVALID_APP_KEY}) only in the body, so a client that maps status codes
 * alone reports "400 Bad Request" for a lapsed session — which is precisely
 * what cost a capture night on 2026-09-01, four seconds after the agent fired,
 * with the log saying nothing that pointed at the session.
 *
 * <p>A session fault also invalidates the cached token on the way past, so the
 * next call logs in again rather than repeating the same refusal.
 */
@Component
class BetfairRest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final RestClient client;
	private final BetfairSession session;
	private final BetfairProperties properties;

	BetfairRest(RestClient.Builder builder, BetfairSession session,
			BetfairProperties properties) {
		this.client = builder.clone().baseUrl(properties.restBaseUrl()).build();
		this.session = session;
		this.properties = properties;
	}

	/** POST a filter to one endpoint and read back its list. */
	<T> List<T> post(String endpoint, Object request, ParameterizedTypeReference<List<T>> type) {
		return client.post()
				.uri(endpoint)
				.header("X-Application", properties.appKey())
				.header("X-Authentication", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				// Betfair answers application/xml when nothing asks otherwise —
				// verified against the live API, where the absent header produced
				// "no suitable HttpMessageConverter ... content type
				// [application/xml]". The Python recorder has always sent this; it
				// is easy to leave out precisely because a mock server answers with
				// whatever the test told it to.
				.accept(MediaType.APPLICATION_JSON)
				.body(request)
				.exchange((req, response) -> {
					HttpStatusCode status = response.getStatusCode();
					if (status.isError()) {
						// An error response with no body at all is possible, and is
						// still an error worth reporting — the empty string reads as
						// "Betfair said nothing", which is itself the finding.
						String body = response.bodyTo(String.class);
						throw refusal(endpoint, status, body == null ? "" : body);
					}
					List<T> body = response.bodyTo(type);
					return body == null ? List.of() : body;
				});
	}

	private BetfairException refusal(String endpoint, HttpStatusCode status, String body) {
		String fault = faultCode(body);
		BetfairException failure = new BetfairException(
				endpoint + " refused: HTTP " + status.value()
						+ (fault.isEmpty() ? " -- " + trim(body) : " (" + fault + ")"),
				fault);
		if (failure.sessionLapsed()) {
			// The token is the problem, so throwing it away here is what makes the
			// retry a different request rather than the same one again.
			session.invalidate();
		}
		return failure;
	}

	/**
	 * Betfair's own error code, out of an error body.
	 *
	 * <p>Best effort by design: an unparseable body is a reason to report the
	 * body, never a reason to fail while reporting a failure.
	 */
	private static String faultCode(String body) {
		try {
			Map<?, ?> parsed = MAPPER.readValue(body, Map.class);
			Object detail = parsed.get("detail");
			if (detail instanceof Map<?, ?> details) {
				for (Object value : details.values()) {
					if (value instanceof Map<?, ?> exception && exception.get("errorCode") != null) {
						return exception.get("errorCode").toString();
					}
				}
			}
			Object faultstring = parsed.get("faultstring");
			return faultstring == null ? "" : faultstring.toString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static String trim(String body) {
		String single = body.replaceAll("\\s+", " ").strip();
		return single.length() > 300 ? single.substring(0, 300) : single;
	}
}
