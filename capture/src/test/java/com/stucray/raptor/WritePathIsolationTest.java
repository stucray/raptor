package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Nothing in raptor can reach a parser, because there is no parser to reach.
 *
 * <p>Nothing that writes to the system of record may depend on a parse being
 * right. The capture path does framing and addressing only — message boundaries
 * and market ids — and carries every payload through untouched. The moment it can
 * reach a parser, a parser bug becomes able to cost data rather than merely a
 * rebuild. The recorder is the sharpest case: it writes the live stream, which
 * exists nowhere else, so a parse bug reaching it destroys data permanently.
 *
 * <p><b>In paddock this was a package rule inside one build</b>: the parsers
 * ({@code paddock-wire}) were a compile dependency of the whole application,
 * because the projection needed them, and only the write-path packages were
 * barred from them. raptor has no projection and no parsers, so the rule
 * becomes structural, and it is held twice here:
 *
 * <ul>
 * <li><b>The parsers are not on the classpath at all.</b> This is the witness
 *     for the rule below: a package rule over code that has nothing to depend on
 *     passes whether or not anything is enforced, and the day someone adds the
 *     parser artifact back as a dependency is the day that becomes untrue.</li>
 * <li><b>No raptor class depends on anything in a {@code wire} or parser
 *     package</b>, whatever its origin — the parsers belong to
 *     overround-analysis, and a copy vendored in here would be the same
 *     failure.</li>
 * </ul>
 */
@DisplayName("The capture path cannot reach a parser")
class WritePathIsolationTest {

	private static final JavaClasses PRODUCTION_CODE = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.stucray.raptor");

	/** The parsers that used to share a build with this code (paddock-wire). */
	@ParameterizedTest
	@ValueSource(strings = {
			"com.stucray.paddock.wire.BasicParser",
			"com.stucray.paddock.wire.CaptureParser",
			"com.stucray.paddock.wire.FootballDataParser",
			"com.stucray.paddock.wire.TolerantLineReader"})
	@DisplayName("no parser is on the classpath")
	void noParserIsOnTheClasspath(String parser) {
		assertThatThrownBy(() -> Class.forName(parser))
				.as("%s is loadable, so something brought the parsers back as a dependency", parser)
				.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	@DisplayName("no raptor class depends on a wire or parser package")
	void nothingDependsOnAParser() {
		ArchRuleDefinition.noClasses()
				.that().resideInAPackage("com.stucray.raptor..")
				.should().dependOnClassesThat().resideInAnyPackage("..wire..", "..parse..", "..parser..")
				.because("raptor stores each source verbatim and never interprets it; a parse "
						+ "bug that can reach the capture path can cost data rather than a rebuild")
				.check(PRODUCTION_CODE);
	}
}
