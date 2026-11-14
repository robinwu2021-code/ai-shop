package ai.neargo.shop.arch;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Distinguishes actual Port capabilities from references to their declared nested value records. */
final class PayReverseDependencySourceAnalysis {
    private PayReverseDependencySourceAnalysis() { }

    static Map<String, Set<String>> nestedValueTypes(String source) throws IOException {
        return parse(source, unit -> {
            Map<String, Set<String>> values = new HashMap<>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree type, Void ignored) {
                    String name = type.getSimpleName().toString();
                    if (type.getKind() == Tree.Kind.INTERFACE && name.endsWith("Port")) {
                        Set<String> nested = new HashSet<>();
                        for (var member : type.getMembers()) {
                            if (member instanceof ClassTree value
                                    && (value.getKind() == Tree.Kind.RECORD || value.getKind() == Tree.Kind.ENUM)) {
                                nested.add(value.getSimpleName().toString());
                            }
                        }
                        values.put(name, Set.copyOf(nested));
                    }
                    return super.visitClass(type, ignored);
                }
            }.scan(unit, null);
            return values;
        });
    }

    static Set<String> capabilities(String source, Map<String, Set<String>> values) throws IOException {
        return parse(source, unit -> {
            Set<String> used = new HashSet<>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitImport(ImportTree imported, Void ignored) {
                    // Static root methods/constants remain capabilities even if referred to by simple name.
                    if (imported.isStatic()) {
                        String importedName = imported.getQualifiedIdentifier().toString();
                        for (var port : values.entrySet()) {
                            int index = importedName.indexOf(port.getKey() + ".");
                            if (index < 0 || index > 0 && importedName.charAt(index - 1) != '.') continue;
                            String member = importedName.substring(index + port.getKey().length() + 1).split("\\.")[0];
                            if (!port.getValue().contains(member)) used.add(port.getKey());
                        }
                    }
                    return null;
                }

                @Override public Void visitMemberSelect(MemberSelectTree member, Void ignored) {
                    String owner = member.getExpression().toString();
                    String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
                    if (values.getOrDefault(simpleOwner, Set.of()).contains(member.getIdentifier().toString())) {
                        return null; // Port.Record (also fully qualified) does not invoke the Port capability.
                    }
                    if (values.containsKey(member.getIdentifier().toString())) used.add(member.getIdentifier().toString());
                    return super.visitMemberSelect(member, ignored);
                }

                @Override public Void visitIdentifier(IdentifierTree identifier, Void ignored) {
                    String name = identifier.getName().toString();
                    if (values.containsKey(name)) used.add(name);
                    return super.visitIdentifier(identifier, ignored);
                }
            }.scan(unit, null);
            return Set.copyOf(used);
        });
    }

    private static <T> T parse(String source, Function<CompilationUnitTree, T> read) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Reverse dependency guard requires a JDK compiler");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///PortSubject.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(file));
            var unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IllegalArgumentException("Unable to parse reverse dependency subject: " + diagnostics.getDiagnostics());
            }
            return read.apply(unit);
        }
    }
}
