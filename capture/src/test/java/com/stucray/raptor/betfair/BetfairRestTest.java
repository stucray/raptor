package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * What a refusal from Betfair has to say for itself.
 *
 * <p>This is the wrapper's whole reason to exist. Betfair returns 400 for a
 * lapsed session and puts the actual cause only in the response body, so a
 * client that reads status codes alone reports "Bad Request" — which is exactly
 * what the log said on 2026-09-01 while a twelve-hour capture window went
 * unrecorded, four seconds after the agent fired.
 */
class BetfairRestTest {

	private static final ParameterizedTypeReference<List<Map<String, Object>>> NODES =
			new ParameterizedTypeReference<>() {};

	private static final String SESSION_FAULT = """
			{"faultcode":"Client","faultstring":"DSC-0018",
			 "detail":{"APINGException":{"errorCode":"INVALID_SESSION_INFORMATION"}}}""";

	private final BetfairSession session = mock(BetfairSession.class);
	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final BetfairRest rest = new BetfairRest(builder, session, properties());

	@Test
	void namesBetfairsOwnFaultCodeRatherThanTheStatus() {
		when(session.token()).thenReturn("token");
		server.expect(requestTo("https://rest.invalid/listMarketCatalogue/"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST)
						.contentType(MediaType.APPLICATION_JSON).body(SESSION_FAULT));

		assertThatThrownBy(() -> rest.post("listMarketCatalogue/", Map.of(), NODES))
				.isInstanceOf(BetfairException.class)
				.hasMessageContaining("INVALID_SESSION_INFORMATION")
				.hasMessageContaining("listMarketCatalogue/");
	}

	/**
	 * A session fault throws the token away on the way past.
	 *
	 * <p>Without this the retry is the identical request with the identical dead
	 * token, which is a loop rather than a recovery.
	 */
	@Test
	void dropsTheTokenWhenTheSessionIsWhatBetfairRefused() {
		when(session.token()).thenReturn("stale");
		server.expect(requestTo("https://rest.invalid/listCompetitions/"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST)
						.contentType(MediaType.APPLICATION_JSON).body(SESSION_FAULT));

		assertThatThrownBy(() -> rest.post("listCompetitions/", Map.of(), NODES))
				.isInstanceOf(BetfairException.class);
		verify(session).invalidate();
	}

	/** A fault that is not about the session leaves a good token alone. */
	@Test
	void keepsTheTokenWhenTheRequestWasWhatBetfairRefused() {
		when(session.token()).thenReturn("good");
		server.expect(requestTo("https://rest.invalid/listMarketBook/"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST)
						.contentType(MediaType.APPLICATION_JSON)
						.body("""
								{"detail":{"APINGException":{"errorCode":"TOO_MUCH_DATA"}}}"""));

		assertThatThrownBy(() -> rest.post("listMarketBook/", Map.of(), NODES))
				.hasMessageContaining("TOO_MUCH_DATA");
		verify(session, never()).invalidate();
	}

	/** An unparseable body is still reported — badly-formed is not silent. */
	@Test
	void reportsABodyItCannotParse() {
		when(session.token()).thenReturn("token");
		server.expect(requestTo("https://rest.invalid/listCompetitions/"))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
						.body("<html><body>maintenance</body></html>"));

		assertThatThrownBy(() -> rest.post("listCompetitions/", Map.of(), NODES))
				.hasMessageContaining("503")
				.hasMessageContaining("maintenance");
	}

	@Test
	void sendsTheAppKeyAndSessionTokenAndReadsTheList() {
		when(session.token()).thenReturn("token-42");
		server.expect(requestTo("https://rest.invalid/listCompetitions/"))
				.andExpect(header("X-Application", "app-key"))
				.andExpect(header("X-Authentication", "token-42"))
				.andRespond(withSuccess("""
						[{"competition":{"id":"117","name":"Spanish La Liga"}}]""",
						MediaType.APPLICATION_JSON));

		List<Map<String, Object>> nodes = rest.post("listCompetitions/", Map.of(), NODES);

		assertThat(nodes).singleElement()
				.satisfies(node -> assertThat(node).containsKey("competition"));
	}

	private static BetfairProperties properties() {
		return new BetfairProperties("app-key", "user", "password", "cert", "key",
				"https://certlogin.invalid", "https://keepalive.invalid", "https://rest.invalid/",
				Path.of("config/capture.properties"));
	}
}
