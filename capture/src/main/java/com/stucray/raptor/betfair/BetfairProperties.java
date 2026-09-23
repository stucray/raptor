package com.stucray.raptor.betfair;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How to reach Betfair, and as whom.
 *
 * <p><b>Every credential defaults to empty and is expected from the
 * environment.</b> {@code bin/up} wraps the application in
 * {@code sops exec-env}, so the encrypted file stays the only place the
 * secrets live: nothing is written to disk decrypted, and a process started
 * without the wrapper simply has no credentials and says so, rather than
 * running with a stale copy someone exported by hand months ago.
 *
 * @param certPemBase64 the client certificate, base64 of the PEM. Base64
 *     because a PEM is multi-line and a multi-line value survives neither a
 *     compose {@code environment:} entry nor most shells intact; one line
 *     always does.
 * @param keyPemBase64 the matching private key, same encoding
 * @param captureConfigFile the paddock-owned {@code capture.properties} — the
 *     same file the launcher reads. Read rather than duplicated: the league
 *     set has one definition, and a second copy is how the record of what a
 *     night was asked to do stops meaning anything.
 */
@ConfigurationProperties("raptor.betfair")
public record BetfairProperties(
		@DefaultValue("") String appKey,
		@DefaultValue("") String username,
		@DefaultValue("") String password,
		@DefaultValue("") String certPemBase64,
		@DefaultValue("") String keyPemBase64,
		@DefaultValue("https://identitysso-cert.betfair.com/api/certlogin") String certLoginUrl,
		@DefaultValue("https://identitysso.betfair.com/api/keepAlive") String keepAliveUrl,
		@DefaultValue("https://api.betfair.com/exchange/betting/rest/v1.0/") String restBaseUrl,
		Path captureConfigFile) {

	/**
	 * Whether this process has what it needs to talk to Betfair at all.
	 *
	 * <p>The honest answer for a developer's plain {@code spring-boot:run}, and
	 * the reason it is a question rather than a startup failure: the read side
	 * is most of this application, it works perfectly well with no session, and
	 * refusing to boot without credentials would make every UI change require
	 * them.
	 */
	public boolean configured() {
		return !appKey.isBlank() && !username.isBlank() && !password.isBlank()
				&& !certPemBase64.isBlank() && !keyPemBase64.isBlank();
	}
}
