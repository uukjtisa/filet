package dev.niccc2007.fixture;

/**
 * The one string M6 edits.
 *
 * It is returned from its own method rather than inlined at the call site so that the
 * constant survives into smali as a single, findable `const-string` instruction. Editing it
 * and seeing the new value come out of a running process is the whole point of the fixture.
 */
public final class Fixture {
    public static String tag() {
        return "ORIGINAL";
    }
}
