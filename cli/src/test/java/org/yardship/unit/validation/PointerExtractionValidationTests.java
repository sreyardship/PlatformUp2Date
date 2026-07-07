package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.cli.outcome.PointerResult;
import org.yardship.cli.outcome.ValidationOutcome;
import org.yardship.cli.validation.PointerExtractionValidation;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PointerExtractionValidation}, the use case behind the {@code pointer}
 * subcommand. These pin the "resolve JSON Pointer, optionally strip pre-release, optionally parse"
 * contract at the cheapest possible seam: pure strings/booleans in, {@link ValidationOutcome} out —
 * no HTTP, no filesystem, no CLI wiring. See {@code PointerCommandWiringTests} (system level) for
 * the end-to-end picocli path.
 *
 * <p>Mirrors the extraction rules of production {@code HttpCurrentSource} (backend): a pointer
 * resolving to a {@code MissingNode} or a non-textual node is treated as "nothing usable" —
 * {@link ValidationOutcome.PointerValidButEmpty} — not a crash, and neither is an unparseable
 * extracted value when {@code --scheme} was given.
 */
class PointerExtractionValidationTests {

    private final PointerExtractionValidation validation = new PointerExtractionValidation();

    private static final VersionParser SEMVER = new VersionParser(VersionScheme.SEMVER);

    @Test
    void pointerFound_textValue_noScheme_isOkExtractionOnly() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"1.2.3\"}", "/version", false, Optional.empty());

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        assertEquals(ValidationOutcome.PointerOk.EXIT_CODE, ok.exitCode());
        PointerResult result = ok.result();
        assertEquals("1.2.3", result.rawText());
        assertFalse(result.schemeRequested(), "no --scheme was given, so no parse should have been attempted");
        assertTrue(result.parsed().isEmpty());
        assertTrue(result.rejectionReason().isEmpty());
    }

    @Test
    void pointerAbsent_missingNode_isValidButEmpty() {
        ValidationOutcome outcome = validation.validate(
                "{\"other\":\"1.2.3\"}", "/version", false, Optional.empty());

        ValidationOutcome.PointerValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.PointerValidButEmpty.class, outcome);
        assertEquals(ValidationOutcome.PointerValidButEmpty.EXIT_CODE, empty.exitCode());
        assertTrue(empty.attempted().isEmpty(),
                "an absent pointer never obtained raw text, so there is nothing to attach");
    }

    @Test
    void pointerResolvesToNonTextual_object_isValidButEmpty() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":{\"major\":1}}", "/version", false, Optional.empty());

        ValidationOutcome.PointerValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.PointerValidButEmpty.class, outcome);
        assertTrue(empty.attempted().isEmpty());
    }

    @Test
    void pointerResolvesToNonTextual_number_isValidButEmpty() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":123}", "/version", false, Optional.empty());

        assertInstanceOf(ValidationOutcome.PointerValidButEmpty.class, outcome);
    }

    @Test
    void bodyNotJson_isValidButEmpty() {
        ValidationOutcome outcome = validation.validate(
                "not json at all", "/version", false, Optional.empty());

        ValidationOutcome.PointerValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.PointerValidButEmpty.class, outcome);
        assertTrue(empty.attempted().isEmpty());
    }

    @Test
    void stripPreRelease_off_reportsRawExtractedValue() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"1.2.3-rc.1\"}", "/version", false, Optional.empty());

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        assertEquals("1.2.3-rc.1", ok.result().rawText());
        assertFalse(ok.result().strippedPreRelease());
    }

    // stripPreRelease_on_reportsStrippedValue was removed (review iteration 2): it exercised
    // --strip-prerelease with NO --scheme, which forced the implementer toward naive text
    // truncation at the first '-' (VersionValue.withoutPreRelease() only exists on a *parsed*
    // value, so there is no well-defined "pre-release" concept for a bare, unparsed string).
    // That truncation corrupted inputs like "1.2.3+build-7" (hyphen in build metadata, no
    // prerelease) and "1.0.0-alpha+001" (lost build metadata entirely). The fix is to make
    // --scheme required whenever --strip-prerelease is set, enforced at the command layer
    // (see PointerCommandWiringTests#stripPrerelease_withoutScheme_isConfigInvalid) — so this
    // use case is never invoked with stripPreRelease=true and parser=Optional.empty() from the
    // real CLI path, and no use-case-level test for that combination is needed here.

    @Test
    void schemeGiven_valueParses_isOkWithParsedVersion() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"1.2.3\"}", "/version", false, Optional.of(SEMVER));

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        PointerResult result = ok.result();
        assertTrue(result.schemeRequested());
        assertTrue(result.parsed().isPresent());
        assertEquals("1.2.3", result.parsed().get().value());
    }

    @Test
    void schemeGiven_valueFailsToParse_isValidButEmpty_withReason_notCrash() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"not-a-semver\"}", "/version", false, Optional.of(SEMVER));

        ValidationOutcome.PointerValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.PointerValidButEmpty.class, outcome);
        assertTrue(empty.attempted().isPresent(),
                "extraction succeeded (there was raw text), only the parse failed, so a partial result should be attached");
        PointerResult attempted = empty.attempted().get();
        assertEquals("not-a-semver", attempted.rawText());
        assertTrue(attempted.rejectionReason().isPresent());
        assertTrue(attempted.parsed().isEmpty());
    }

    @Test
    void noSchemeGiven_extractionSucceeds_noParseAttempted() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"totally-not-a-version-but-thats-fine\"}", "/version", false, Optional.empty());

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        assertFalse(ok.result().schemeRequested());
        assertEquals("totally-not-a-version-but-thats-fine", ok.result().rawText());
    }

    @Test
    void stripPreRelease_andScheme_bothApply_rawTextUnchanged_parsedValueStripped() {
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"1.2.3-rc.1\"}", "/version", true, Optional.of(SEMVER));

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        assertEquals("1.2.3-rc.1", ok.result().rawText(),
                "rawText must remain the original extracted text, unmodified by --strip-prerelease");
        assertTrue(ok.result().parsed().isPresent());
        assertEquals("1.2.3", ok.result().parsed().get().value(),
                "the parsed value reflects VersionValue.withoutPreRelease(), applied after parsing");
    }

    @Test
    void stripPreRelease_buildMetadataWithHyphen_noPreRelease_reportsUnchangedValue() {
        // Reviewer counter-example: naive text truncation at the first '-' used to corrupt this
        // into "1.2.3+build" even though there is no actual pre-release segment.
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"1.2.3+build-7\"}", "/version", true, Optional.of(SEMVER));

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        assertEquals("1.2.3+build-7", ok.result().rawText());
        assertEquals("1.2.3+build-7", ok.result().parsed().get().value(),
                "no pre-release segment exists, so withoutPreRelease() must leave the value unchanged");
    }

    @Test
    void stripPreRelease_preReleaseAndBuildMetadata_preservesBuildMetadata() {
        // Reviewer counter-example: naive text truncation used to drop build metadata entirely,
        // turning "1.0.0-alpha+001" into "1.0.0" instead of the correct "1.0.0+001".
        ValidationOutcome outcome = validation.validate(
                "{\"version\":\"1.0.0-alpha+001\"}", "/version", true, Optional.of(SEMVER));

        ValidationOutcome.PointerOk ok = assertInstanceOf(ValidationOutcome.PointerOk.class, outcome);
        assertEquals("1.0.0-alpha+001", ok.result().rawText());
        assertEquals("1.0.0+001", ok.result().parsed().get().value(),
                "build metadata must be preserved when only the pre-release segment is stripped");
    }
}
