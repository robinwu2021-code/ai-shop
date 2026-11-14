package ai.neargo.shop.arch;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Parses real method bodies so comments, strings and neighboring helpers cannot fabricate audit coverage. */
final class PayAuditSourceAnalysis {
    record MethodAudit(String name, boolean operator, boolean audited) {}
    private static final Set<String> IDENTITIES = Set.of("operator", "operatorNo");

    static List<MethodAudit> methods(String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Audit guard requires a JDK compiler");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///AuditSubject.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var out = new ArrayList<MethodAudit>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(file));
            for (CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitClass(ClassTree type, Void ignored) {
                        var fields = new HashSet<String>();
                        var helpers = new HashMap<String, List<MethodTree>>();
                        var methods = new ArrayList<MethodTree>();
                        for (var member : type.getMembers()) {
                            if (member instanceof VariableTree field && (field.getType().toString().equals("AuditLogPort")
                                    || field.getType().toString().equals("ai.neargo.shop.spi.platform.AuditLogPort"))) {
                                fields.add(field.getName().toString());
                            } else if (member instanceof MethodTree method && method.getBody() != null
                                    && method.getReturnType() != null) {
                                methods.add(method);
                                if (method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                                    helpers.computeIfAbsent(method.getName().toString(), key -> new ArrayList<>()).add(method);
                                }
                            }
                        }
                        for (var method : methods) {
                            if (!method.getModifiers().getFlags().contains(Modifier.PUBLIC)) continue;
                            out.add(new MethodAudit(method.getName().toString(), hasOperator(method),
                                    hasAudit(method, fields, helpers, new HashSet<>())));
                        }
                        return super.visitClass(type, ignored);
                    }
                }.scan(unit, null);
            }
        }
        if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)) {
            throw new IllegalArgumentException("Unable to parse audit subject: " + diagnostics.getDiagnostics());
        }
        return out;
    }

    private static boolean hasOperator(MethodTree method) {
        var parameters = new HashSet<String>();
        for (var p : method.getParameters()) {
            if (IDENTITIES.contains(p.getName().toString())) parameters.add(p.getName().toString());
        }
        var found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree type, Void ignored) { return null; }
            @Override public Void visitIdentifier(IdentifierTree identifier, Void ignored) {
                if (parameters.contains(identifier.getName().toString())) found[0] = true;
                return super.visitIdentifier(identifier, ignored);
            }
            @Override public Void visitVariable(VariableTree variable, Void ignored) {
                if (IDENTITIES.contains(variable.getName().toString())) found[0] = true;
                return super.visitVariable(variable, ignored);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                String name = call.getMethodSelect() instanceof MemberSelectTree select
                        ? select.getIdentifier().toString() : call.getMethodSelect().toString();
                if (call.getArguments().isEmpty()
                        && (name.equals("currentUserNo") || IDENTITIES.contains(name))) found[0] = true;
                return super.visitMethodInvocation(call, ignored);
            }
        }.scan(method.getBody(), null);
        return found[0];
    }

    private static boolean hasAudit(MethodTree method, Set<String> fields,
            Map<String, List<MethodTree>> helpers, Set<MethodTree> visited) {
        if (!visited.add(method)) return false;
        var found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree type, Void ignored) { return null; }
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                var select = call.getMethodSelect();
                String helper = null;
                if (select instanceof MemberSelectTree member) {
                    if (member.getIdentifier().contentEquals("record")
                            && (fields.contains(member.getExpression().toString())
                                || member.getExpression() instanceof MemberSelectTree receiver
                                    && receiver.getExpression().toString().equals("this")
                                    && fields.contains(receiver.getIdentifier().toString()))) found[0] = true;
                    if (member.getExpression().toString().equals("this")) helper = member.getIdentifier().toString();
                } else if (select instanceof IdentifierTree identifier) helper = identifier.getName().toString();
                if (helper != null) {
                    var candidates = helpers.getOrDefault(helper, List.of()).stream()
                            .filter(candidate -> candidate.getParameters().size() == call.getArguments().size()).toList();
                    // Unresolved overloads cannot establish which helper is invoked.
                    if (candidates.size() == 1 && hasAudit(candidates.getFirst(), fields, helpers, visited)) {
                        found[0] = true;
                    }
                }
                return super.visitMethodInvocation(call, ignored);
            }
        }.scan(method.getBody(), null);
        return found[0];
    }
}
