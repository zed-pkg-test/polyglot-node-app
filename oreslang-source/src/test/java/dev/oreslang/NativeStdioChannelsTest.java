package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs guest Oreslang without Java interop. Java here is only the test driver,
 * since the reference interpreter is hosted by Graal/Truffle.
 */
@Timeout(30)
final class NativeStdioChannelsTest {
    private record Output(String stdout, String stderr) { }

    private static Output run(String source, String stdin) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Source code = Source.newBuilder(OresLanguage.ID, source, "stdio-channels.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)))
                // VM diagnostics are not application stderr. Keep the
                // exact process.stderr data contract independent of
                // experimental virtual-thread engine warnings.
                .logHandler(new ByteArrayOutputStream())
                .out(stdout).err(stderr).build()) {
            context.eval(code);
        }
        return new Output(
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void stdinCleanEofPreservesBlankAndUnterminatedFinalLines() throws Exception {
        String code = """
                pub routine main(): void {
                  for const line of process.stdin {
                    writech process.stdout, "[" + line + "]\\n";
                  }
                  return;
                }
                """;
        assertEquals("", run(code, "").stdout());
        assertEquals("[]\n[]\n[last]\n", run(code, "\n\nlast").stdout());
        assertEquals("[CRLF]\n", run(code, "CRLF\r\n").stdout());
    }

    @Test
    void malformedUtf8PropagatesAsInputFailureNotCleanEof() throws Exception {
        String code = """
                pub routine main(): void {
                  for const line of stdin.in {
                    writech stdio.out, line + "\\n";
                  }
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(code);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "invalid-utf8.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        Exception thrown = assertThrows(Exception.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .in(new ByteArrayInputStream(new byte[]{(byte) 0xC3, (byte) 0x28}))
                    .out(output).build()) {
                context.eval(source);
            }
        });
        assertTrue(thrown.toString().contains("stdio")
                || thrown.toString().contains("Input")
                || thrown.getCause() != null,
                "invalid UTF-8 must fail instead of silently completing stdin; observed: "
                        + thrown.getClass().getName() + ": " + thrown);
    }

    @Test
    void nativePipelineFinishesOnEofAndWritesDistinctDescriptors() throws Exception {
        String code = Files.readString(Path.of("examples/native-stdio-channels.ores"));
        Output result = run(code, "alpha\nbeta\n");
        assertEquals("alpha\nbeta\n", result.stdout());
        assertEquals("stdin closed\n", result.stderr());
    }

    @Test
    void nativeObservableConsumesClosedBufferedChannelExactlyOnce() throws Exception {
        Output result = run("""
                                 // Observable is a native global prelude module.
                pub routine main(): void {
                  val Channel<int> numbers = Channel.new<int>(2);
                  writech numbers, 4;
                  writech numbers, 5;
                  numbers.close();
                  for const item of Observable.from_ch(numbers) {
                    stdio.stdout.write(item);
                  }
                  return;
                }
                """, "");
        assertEquals("45", result.stdout());
    }

    @Test
    void directChannelIterationUsesSuspendableSourceTurns() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  for const line of stdio.stdin {
                    stdio.println(line);
                  }
                  return;
                }
                """, "one\ntwo\n");
        assertEquals("one\ntwo\n", result.stdout());
    }

    @Test
    void legacyReadLineSharesChannelAndReturnsOptionAtEof() throws Exception {
        Output result = run("""
                pub async routine main(): void {
                  val Option<String> first = await stdio.stdin.read_line();
                  val Option<String> eof = await stdio.in.read_line();
                  stdio.stdout.write(first.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(eof.is_none());
                  return;
                }
                """, "one\n");
        assertEquals("one:true", result.stdout());
    }

    @Test
    void nativeNonBlockingOutputReturnsAnAwaitableAdmissionFuture() throws Exception {
        Output result = run("""
                                 // Stdio is a native global prelude module; no import required.
                pub async routine main(): void {
                  await Stdio.println("first");
                  await Stdio.eprintln("error");
                  return;
                }
                """, "");
        assertEquals("first\n", result.stdout());
        assertEquals("error\n", result.stderr());
    }

    @Test
    void nativeFormattedLinesAndFlushBarriersPreserveOrdering() throws Exception {
        Output result = run("""
                                 // Stdio is a native global prelude module; no import required.
                pub routine main(): void {
                  Stdio.println_parts_blocking(["hello", "-", "world"]);
                  Stdio.println_blocking("next");
                  Stdio.flush_blocking();
                  Stdio.eprintln_blocking("warning");
                  Stdio.err_flush_blocking();
                  return;
                }
                """, "");
        assertEquals("hello-world\nnext\n", result.stdout());
        assertEquals("warning\n", result.stderr());
    }

    @Test
    void observableRendezvousPullConsumesSingleElement() throws Exception {
        Output result = run("""
                                 // Observable is a native global prelude module.
                pub routine main(): void {
                  val Channel<int> inbox = Channel.new<int>(0);
                  val Future<void> pending = nb writech inbox, 9;
                  val Iterator<int> stream = Observable.from_ch(inbox);
                  for const value of stream {
                    stdio.stdout.write(value);
                    break;
                  }
                  inbox.close();
                  return;
                }
                """, "");
        assertEquals("9", result.stdout());
    }

    @Test
    void outputChannelRejectsReadDirection() {
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  val msg = readch stdio.out;
                  return;
                }
                """, ""));
    }

    @Test
    void inputChannelRejectsWriteDirection() {
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  writech stdio.in, "illegal";
                  return;
                }
                """, ""));
    }

    @Test
    void stdinOversizedLineIsAnErrorNotEof() {
        String huge = "x".repeat(65_538) + "\n";
        Exception problem = assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  for const line of stdio.in {
                    stdio.println(line);
                  }
                  return;
                }
                """, huge));
        assertTrue(problem.toString().contains("stdio")
                || problem.toString().contains("stdin")
                || problem.toString().contains("line")
                || problem.getCause() != null,
                "oversized input failure was: " + problem.getClass().getName()
                        + ": " + problem);
    }


    @Test
    void outputFrameLimitRejectsAsciiAndMultibytePayloadsBeforeAdmission() {
        for (String[] sample : new String[][]{
                {"stdio.out", "x".repeat(65_537)},
                {"process.stderr", "é".repeat(32_769)}
        }) {
            String code = "pub routine main(): void { writech "
                    + sample[0] + ", \"" + sample[1] + "\"; return; }";
            assertThrows(Exception.class, () -> run(code, ""),
                    "oversized UTF-8 frame should not be admitted: " + sample[0]);
        }
    }

    @Test
    void outputFrameAtExactUtf8LimitIsAccepted() throws Exception {
        String payload = "x".repeat(65_536);
        Output result = run("pub routine main(): void { writech stdio.out, \""
                + payload + "\"; return; }", "");
        assertEquals(payload, result.stdout());
    }

    @Test
    void stdinLineByteLimitRejectsLargeMultibyteInput() {
        String input = "é".repeat(32_769) + "\n";
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  for const line of stdio.in {
                    writech stdio.out, line;
                  }
                  return;
                }
                """, input));
    }

    @Test
    void processAndStdioChannelsAreTheSameGlobalObjects() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  stdio.stdout.write(stdio.in == process.stdin);
                  stdio.stdout.write(":");
                  stdio.stdout.write(stdio.out == process.stdout);
                  stdio.stdout.write(":");
                  stdio.stdout.write(stdio.err == process.stderr);
                  stdio.stdout.write(":");
                  stdio.stdout.write(stdin.in == stdio.in);
                  return;
                }
                """, "");
        assertEquals("true:true:true:true", result.stdout());
    }

    @Test
    void globalObservableRequiresNoImports() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  val Channel<int> source = Channel.new<int>(2);
                  writech source, 3;
                  writech source, 4;
                  source.close();
                  for const item of Observable.from_ch(source) {
                    stdio.stdout.write(item);
                  }
                  return;
                }
                """, "");
        assertEquals("34", result.stdout());
    }

    @Test
    void globalObservableFromProcessStdinCompletesAtEof() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  for const line of Observable.from_ch(process.stdin) {
                    stdio.println(line);
                  }
                  return;
                }
                """, "north\nsouth\n");
        assertEquals("north\nsouth\n", result.stdout());
    }

    @Test
    void userCannotShadowTrustedReadableStreamClass() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub define class ReadableStream<T> as
                  val Channel<T> source;
                end
                pub routine main(): void {
                  val reader = stdio.in.stream();
                  return;
                }
                """));
    }

    @Test
    void userCannotShadowGlobalObservableModule() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module Observable as
                  pub fnc from_ch(String x): String { return x; }
                end
                pub routine main(): void {
                  val v = Observable.from_ch("unexpected");
                  return;
                }
                """));
    }

    @Test
    void stdinStreamReturnsNativeReadableStreamWithoutImports() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  val ReadableStream<String> lines = stdin.in.stream();
                  for const line of lines {
                    stdio.println(line);
                  }
                  return;
                }
                """, "red\nblue\n");
        assertEquals("red\nblue\n", result.stdout());
    }

    @Test
    void processStdinStreamAliasWorksWithoutImports() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  for const line of process.stdin.stream() {
                    stdio.println(line);
                  }
                  return;
                }
                """, "hello\n");
        assertEquals("hello\n", result.stdout());
    }

    @Test
    void teeWritesToBothOutputDescriptorsWithoutReadingEither() throws Exception {
        Output result = run("""
                pub routine main(): void {
                  val WritableTee mirrored = stdio.out.tee(stdio.err);
                  await mirrored.write("copied\\n");
                  return;
                }
                """, "");
        assertEquals("copied\n", result.stdout());
        assertEquals("copied\n", result.stderr());
    }

    @Test
    void directDescriptorMisuseIsRejectedByTypeChecker() {
        for (String operation : new String[]{
                "readch stdio.out", "readch stdio.err",
                "readch process.stdout", "readch process.stderr",
                "writech stdio.in, \"x\"",
                "writech process.stdin, \"x\""
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub routine main(): void { " + operation + "; return; }"));
        }
    }

    @Test
    void noArgumentOutputTeeIsRejectedStatically() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  stdio.out.tee();
                  return;
                }
                """));
    }

    @Test
    void syncGeneratorConsumingStdinRunsOnSuspendedSourceTurns() throws Exception {
        Output result = run("""
                pub gen* read_stdin(): Iterator<String> {
                  for const line of process.stdin {
                    yield line;
                  }
                  return;
                }
                pub routine main(): void {
                  for const line of read_stdin() {
                    writech stdio.out, line + "\\n";
                  }
                  writech stdio.err, "eof\\n";
                  return;
                }
                """, "one\ntwo\n");
        assertEquals("one\n" + "two\n", result.stdout());
        assertEquals("eof\n", result.stderr());
    }

    @Test
    void syncGeneratorEarlyBreakCancelsRemainingPull() throws Exception {
        Output result = run("""
                pub gen* read_stdin(): Iterator<String> {
                  for const line of stdin.in {
                    yield line;
                  }
                  return;
                }
                pub routine main(): void {
                  for const line of read_stdin() {
                    writech stdio.out, line + "\\n";
                    break;
                  }
                  return;
                }
                """, "first\nsecond\n");
        assertEquals("first\n", result.stdout());
    }

    @Test
    void sourceAllowsSynchronousGeneratorWithoutAsync() throws Exception {
        OresCompiler.parseAndTypeCheck("""
                pub gen* stdin_lines(): Iterator<String> {
                  for const line of stdio.in {
                    yield line;
                  }
                  return;
                }
                pub routine main(): void { return; }
                """);
    }
}
