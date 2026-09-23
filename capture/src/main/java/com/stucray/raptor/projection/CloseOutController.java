package com.stucray.raptor.projection;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The close-out's only trigger since #334: launchd's
 * close-out launchd agent POSTs here nightly, and on wake when the
 * night's firing passed during sleep. Localhost-only like every {@code /ops/*}
 * surface, and the same request by hand is how to run one early.
 *
 * <p>Synchronous: the sweep is 22 conditional requests and returns in seconds,
 * and a caller that waits for the verdict can log it.
 *
 * <p><b>409 when a run is already in flight</b>, which is not a failure — the
 * running one is doing what was asked for. A failed sweep is a 200 with
 * {@code FAILED} in the body: the request did what it was for, which is to run a
 * close-out and record it, and the heartbeat reports the verdict from the ledger.
 */
@RestController
@ConditionalOnProperty(name = "raptor.close-out.enabled", havingValue = "true")
class CloseOutController {

	private final NightlyCloseOut closeOut;

	CloseOutController(NightlyCloseOut closeOut) {
		this.closeOut = closeOut;
	}

	@PostMapping("/ops/close-out")
	ResponseEntity<Map<String, Object>> closeOut() {
		NightlyCloseOut.Result result = closeOut.closeOut();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", result.status().name());
		body.put("archiveFiles", result.archiveFiles());
		body.put("detail", result.detail() == null ? "" : result.detail());
		HttpStatus status = result.status() == NightlyCloseOut.Status.ALREADY_RUNNING
				? HttpStatus.CONFLICT : HttpStatus.OK;
		return ResponseEntity.status(status).body(body);
	}
}
