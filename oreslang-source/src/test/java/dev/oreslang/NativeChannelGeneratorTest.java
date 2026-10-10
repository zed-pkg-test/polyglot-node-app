package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Contract test: a sync gen* can suspend on readch, preserving channel backpressure.
 * This does NOT yet prove that stdio.stdin is backed by a channel.
 */
@Timeout(30)
final class NativeChannelGeneratorTest {

    @Test
    void syncGenStarReadsRendezvousWithoutAsyncOrAwait() throws Exception {
        String program = Files.readString(Path.of("examples/channel-stdin-sync-generator.ores"));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "channel-stdin-sync-generator.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("alpha\nbeta\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void syncGenStarShorthandParsesWithoutFncKeyword() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub gen* read_two(Channel<int> input): Iterator<int> {
                  yield readch input;
                  yield readch input;
                  return;
                }
                pub routine main(): void { return; }
                """));
    }

    @Test
    void nonChannelCannotBeReadWithReadch() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                  pub gen* broken(): Iterator<String> {
                    yield readch "not a channel";
                    return;
                  }
                  pub routine main(): void { return; }
                  """));
    }
}
