package io.lightine.tessera.smoke;

import io.lightine.tessera.mrz.parsing.MrzParser;
import io.lightine.tessera.mrz.parsing.ParseResult;
import java.util.List;
import kotlin.time.Instant;

/**
 * Compiles only if Maven put tessera-mrz-core-jvm, tessera-types-jvm, and kotlin-stdlib on the
 * compile classpath from the root tessera-mrz-core dependency. Parses the ICAO Doc 9303 TD3
 * specimen (published test data, not a real document).
 */
public final class ParseSpecimen {
    private ParseSpecimen() {}

    public static void main(String[] args) {
        List<String> lines = List.of(
                "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
                "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"); // mrz-ok: ICAO specimen
        ParseResult result = MrzParser.INSTANCE.parseTD3(lines, Instant.Companion.parse("2026-05-04T12:00:00Z"));
        if (!(result instanceof ParseResult.Success)) {
            throw new IllegalStateException("Expected the TD3 specimen to parse, got " + result);
        }
        System.out.println("Parsed the TD3 specimen: " + result.getClass().getSimpleName());
    }
}
