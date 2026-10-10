package dev.oreslang.stdlib;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes selected Oreslang-owned stdlib namespaces/types part of the prelude.
 * This only assembles parsed .ores declarations; it implements no observable
 * or stream semantics in Java. Existing explicit imports are respected.
 *
 * Loading is demand driven to avoid changing unrelated compiler ASTs and
 * existing source programs merely because a stdlib file was packaged.
 */
public final class NativePrelude {
    private NativePrelude() { }

    public static Ast.Program attach(Ast.Program program, String source) {
        // The stdlib audit compiles each packaged .ores file in isolation.
        // The declaration inside that exact trusted file is not user shadowing.
        // This exception is byte-for-byte only; edited/redeclared modules
        // still fail the reserved-namespace admission check below.
        if (isPackagedSource(source)) return program;
        boolean needObservable = source.matches("(?s).*\\bObservable\\b.*")
                || source.matches("(?s).*\\.stream\\s*\\(.*")
                || source.matches("(?s).*\\.tee\\s*\\(.*");
        boolean needStreams = source.matches("(?s).*\\bReadableStream\\b.*")
                || source.matches("(?s).*\\bWritableTee\\b.*")
                || source.matches("(?s).*\\.stream\\s*\\(.*")
                || source.matches("(?s).*\\.tee\\s*\\(.*");
        boolean needStdio = source.matches("(?s).*\\bStdio\\b.*");
        if (!needObservable && !needStreams && !needStdio) return program;

        // These names are not extension points. In particular, a local
        // ReadableStream class must not be invoked by the trusted Channel.stream
        // primitive simply because it has the same unqualified name.
        for (Ast.ModuleDecl module : program.modules()) {
            if ((needObservable && module.name().equals("Observable"))
                    || (needStdio && module.name().equals("Stdio"))) {
                throw new IllegalArgumentException(
                        "cannot redeclare reserved Oreslang standard module " + module.name());
            }
            if (needStreams) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ClassDecl klass
                            && (klass.name().equals("ReadableStream")
                                || klass.name().equals("WritableTee"))) {
                        throw new IllegalArgumentException(
                                "cannot redeclare reserved Oreslang stream type " + klass.name());
                    }
                }
            }
        }
        Set<String> present = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            present.add(module.name());
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) present.add(klass.name());
            }
        }
        for (Ast.ImportDecl imported : program.imports()) {
            if (!imported.wildcard()) {
                for (String name : imported.names()) {
                    String exposed = imported.namespace() == null ? name : imported.namespace();
                    if (needStreams
                            && (exposed.equals("ReadableStream") || exposed.equals("WritableTee"))) {
                        throw new IllegalArgumentException(
                                "native stream types are globally provided; do not import " + exposed);
                    }
                    if (needObservable && exposed.equals("Observable")
                            && !imported.path().equals("std/observable")) {
                        throw new IllegalArgumentException("Observable is a reserved standard module");
                    }
                    if (needStdio && exposed.equals("Stdio")
                            && !imported.path().equals("std/stdio")) {
                        throw new IllegalArgumentException("Stdio is a reserved standard module");
                    }
                    present.add(imported.namespace() == null ? name : imported.namespace());
                }
            }
        }
        List<Ast.ModuleDecl> merged = new ArrayList<>(program.modules());
        if (needStdio && !present.contains("Stdio")) {
            Ast.Program stdlib = parsePackaged("std/stdio");
            for (Ast.ModuleDecl module : stdlib.modules()) {
                if (module.name().equals("Stdio")) {
                    merged.add(module);
                    present.add("Stdio");
                }
            }
        }
        if (needObservable && !present.contains("Observable")) {
            Ast.Program stdlib = parsePackaged("std/observable");
            for (Ast.ModuleDecl module : stdlib.modules()) {
                if (module.name().equals("Observable")) {
                    merged.add(module);
                    present.add("Observable");
                }
            }
        }
        if (needStreams) {
            Ast.Program stdlib = parsePackaged("std/streams");
            List<Ast.Decl> additions = new ArrayList<>();
            for (Ast.ModuleDecl module : stdlib.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ClassDecl klass
                            && present.add(klass.name())) additions.add(declaration);
                }
            }
            if (!additions.isEmpty()) {
                merged.add(new Ast.ModuleDecl("$native_prelude", additions));
            }
        }
        return new Ast.Program(program.namespace(), program.imports(), merged);
    }

    private static boolean isPackagedSource(String candidate) {
        for (String module : List.of("std/stdio", "std/observable", "std/streams")) {
            try {
                if (candidate.equals(CoreLibrarySources.source(module))) return true;
            } catch (IOException failure) {
                throw new IllegalStateException("could not validate packaged stdlib source: " + module,
                        failure);
            }
        }
        return false;
    }

    private static Ast.Program parsePackaged(String path) {
        try {
            String source = CoreLibrarySources.source(path);
            // Parse raw: never recursively expand the prelude while loading it.
            return new Parser(new Lexer(source).scan()).parseProgram();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load packaged Oreslang prelude: " + path, failure);
        }
    }
}
