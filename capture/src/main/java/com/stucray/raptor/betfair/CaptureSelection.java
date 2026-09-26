package com.stucray.raptor.betfair;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the programme is asking to capture: leagues, market types, control set.
 *
 * <p>Read from the paddock-owned {@code capture.properties} — <b>the same file
 * the launcher reads</b>, not a copy. The league set has one definition on
 * purpose: git is its audit trail, and every night's ledger row records what
 * that definition actually resolved to, which is only worth anything while
 * there is exactly one place the answer comes from.
 *
 * <p>Re-read on every poll rather than bound once at startup, for the reason
 * the file exists: changing the league set should need no restart, and a change
 * that needed one to take effect would make the running recorder quietly
 * disagree with the file for as long as nobody noticed.
 *
 * <p>The file read here is the <b>deployed</b> copy, not the repository's
 * (#19): {@code bin/deploy-config} writes it from the committed version, because
 * re-reading the working tree every poll let a branch checkout reconfigure live
 * capture.
 *
 * <p>The backend has its own reader of this file for the health screen. That
 * duplication is deliberate and temporary — the two modules cannot share code
 * in this direction, and it collapses at the S8 cutover when the launchd path
 * retires. Both read the same file, so there is still one definition; what is
 * duplicated is the parsing, not the truth.
 */
@Component
class CaptureSelection {

	private static final Logger log = LoggerFactory.getLogger(CaptureSelection.class);

	private final Path file;

	CaptureSelection(BetfairProperties properties) {
		this.file = properties.captureConfigFile();
	}

	Selection current() {
		Properties loaded = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			loaded.load(reader);
		} catch (IOException e) {
			// Empty rather than a guess. Capturing on built-in defaults nobody
			// chose looks identical afterwards to capturing on a real
			// configuration, which is the failure this file was created to end.
			log.warn("capture configuration unreadable at {} ({}); nothing will be requested",
					file, e.toString());
			return Selection.EMPTY;
		}
		return new Selection(list(loaded, "capture.leagues"),
				list(loaded, "capture.market-types"),
				list(loaded, "capture.control-countries"));
	}

	private static List<String> list(Properties properties, String key) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
	}

	/**
	 * @param leagues competition NAMES, resolved to ids at run time. Names
	 *     because the second tiers' ids are season-scoped: a pinned list keeps
	 *     working for the glamour divisions and silently stops capturing Serie B
	 *     and Segunda at a rollover, which is the worst failure shape available
	 *     — quiet, partial, and invisible until someone counts rows months later.
	 */
	record Selection(List<String> leagues, List<String> marketTypes,
			List<String> controlCountries) {

		static final Selection EMPTY = new Selection(List.of(), List.of(), List.of());

		Selection {
			leagues = List.copyOf(leagues);
			marketTypes = List.copyOf(marketTypes);
			controlCountries = List.copyOf(controlCountries);
		}

		boolean isEmpty() {
			return leagues.isEmpty() && controlCountries.isEmpty();
		}
	}
}
