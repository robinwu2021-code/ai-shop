package ai.neargo.shop.arch;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** Parse declarations rather than annotation-looking comments, strings or adjacent methods. */
final class BizEndpointSource {
    record Method(String owner, String name, Map<String, String> annotations, List<String> calls) {
        boolean calls(String invocation) { return calls.contains(compact(invocation)); }
        boolean bizGuard() {
            return authorizationRequires(annotations.get("PreAuthorize"), "perm", Set.of("canBiz", "canAnyBiz"));
        }
    }
    record Endpoint(String path, Method method) { }
    record Model(List<Endpoint> endpoints, List<Method> methods) { }

    static Model parse(String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for permission source checks");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///PermissionSource.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        List<Endpoint> endpoints = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(input));
            for (var unit : task.parse()) new TreeScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree type, Void unused) {
                    var classAnnotations = annotations(type.getModifiers().getAnnotations());
                    List<String> prefixes = paths(type.getModifiers().getAnnotations(), "RequestMapping");
                    if (prefixes.isEmpty()) prefixes = List.of("");
                    for (var member : type.getMembers()) {
                        if (!(member instanceof MethodTree method)) continue;
                        var all = new LinkedHashMap<>(classAnnotations);
                        all.putAll(annotations(method.getModifiers().getAnnotations()));
                        List<String> calls = new ArrayList<>();
                        if (method.getBody() != null) new TreeScanner<Void, Void>() {
                            @Override public Void visitClass(ClassTree nested, Void ignored) { return null; }
                            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                                calls.add(compact(call.toString()));
                                return super.visitMethodInvocation(call, ignored);
                            }
                        }.scan(method.getBody(), null);
                        var parsed = new Method(type.getSimpleName().toString(), method.getName().toString(), Map.copyOf(all), List.copyOf(calls));
                        methods.add(parsed);
                        for (String mapping : List.of("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping")) {
                            for (String path : paths(method.getModifiers().getAnnotations(), mapping)) {
                                for (String prefix : prefixes) {
                                    String full = path.startsWith("/biz/") ? path : prefix + path;
                                    if (full.startsWith("/biz/")) endpoints.add(new Endpoint(full, parsed));
                                }
                            }
                        }
                    }
                    return super.visitClass(type, unused);
                }
            }.scan(unit, null);
            var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
            if (!errors.isEmpty()) throw new IllegalStateException("Cannot parse permission source: " + errors);
        }
        return new Model(List.copyOf(endpoints), List.copyOf(methods));
    }

    static Model read(Path path) throws IOException { return parse(Files.readString(path)); }
    static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static String simple(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
    private static Map<String, String> annotations(List<? extends AnnotationTree> annotations) {
        Map<String, String> out = new LinkedHashMap<>();
        annotations.forEach(a -> {
            if (simple(a).equals("PreAuthorize")) {
                if (a.getArguments().size() != 1) throw new IllegalStateException("Unresolved authorization annotation");
                ExpressionTree value = a.getArguments().getFirst();
                if (value instanceof AssignmentTree named) value = named.getExpression();
                out.put("PreAuthorize", annotationString(value));
            } else out.put(simple(a), compact(a.getArguments().toString()));
        });
        return out;
    }
    /** Resolve only Java string constants; no annotation or SpEL expression is executed. */
    private static String annotationString(ExpressionTree expression) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value) return value;
        if (expression instanceof ParenthesizedTree parenthesized) return annotationString(parenthesized.getExpression());
        if (expression instanceof BinaryTree binary && binary.getKind() == com.sun.source.tree.Tree.Kind.PLUS)
            return annotationString(binary.getLeftOperand()) + annotationString(binary.getRightOperand());
        if (expression instanceof MemberSelectTree field) {
            String owner = field.getExpression().toString();
            owner = owner.substring(owner.lastIndexOf('.') + 1);
            if (Set.of("BizPerms", "Perms").contains(owner)) {
                try {
                    Object value = Class.forName("ai.neargo.shop.auth." + owner).getField(field.getIdentifier().toString()).get(null);
                    if (value instanceof String string) return string;
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unknown permission constant", failure); }
            }
        }
        throw new IllegalStateException("Unresolved authorization expression: " + expression);
    }
    static boolean authorizationRequires(String expression, String bean, Set<String> methods) {
        if (expression == null) return false;
        try { return requires(new SpelExpressionParser().parseRaw(expression).getAST(), bean, methods); }
        catch (org.springframework.expression.ParseException invalid) { return false; }
    }
    private static boolean requires(SpelNode node, String bean, Set<String> methods) {
        // Any allow through OR must be protected; AND is protected if either conjunct requires it.
        if (node instanceof OpOr) return requires(node.getChild(0), bean, methods) && requires(node.getChild(1), bean, methods);
        if (node instanceof OpAnd) return requires(node.getChild(0), bean, methods) || requires(node.getChild(1), bean, methods);
        if (!(node instanceof CompoundExpression) || node.getChildCount() != 2
                || !(node.getChild(0) instanceof BeanReference reference) || !reference.getName().equals(bean)
                || !(node.getChild(1) instanceof MethodReference method) || !methods.contains(method.getName())
                || method.isNullSafe()) return false;
        if (bean.equals("managementAccess")) return method.getChildCount() == 0;
        if (method.getChildCount() == 0) return false;
        for (int i = 0; i < method.getChildCount(); i++) if (!(method.getChild(i) instanceof StringLiteral)) return false;
        return true;
    }

    private static List<String> paths(List<? extends AnnotationTree> annotations, String mapping) {
        List<String> out = new ArrayList<>();
        for (var annotation : annotations) {
            if (!simple(annotation).equals(mapping)) continue;
            if (annotation.getArguments().isEmpty()) out.add("");
            int before = out.size();
            for (ExpressionTree argument : annotation.getArguments()) {
                if (argument instanceof AssignmentTree named) {
                    if (!List.of("value", "path").contains(named.getVariable().toString())) continue;
                    argument = named.getExpression();
                }
                literalPaths(argument, out);
            }
            if (out.size() == before) out.add("");
        }
        return out;
    }
    private static void literalPaths(ExpressionTree expression, List<String> paths) {
        if (expression instanceof LiteralTree value && value.getValue() instanceof String path) paths.add(path);
        else if (expression instanceof NewArrayTree array && array.getInitializers() != null) array.getInitializers().forEach(e -> literalPaths(e, paths));
        else throw new IllegalStateException("Permission mapping must resolve explicit paths: " + expression);
    }
}
