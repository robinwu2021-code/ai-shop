package ai.neargo.shop.trade.service.impl;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.beans.Introspector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

/** Core is compiled by the retail reactor; optional Beauty declarations are checked from their real source. */
class ExecutionBeanNamesTest {
    @Test
    void embeddedExecutionOwnersHaveDistinctBeanNames() throws Exception {
        Path root = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent().getParent().getParent();
        var metadata = new CachingMetadataReaderFactory();
        var names = new ArrayList<String>();
        for (String type : new String[] {"policy.ServiceExecutionSchemaVerifier",
                "service.impl.ServiceExecutionFenceCoordinator", "job.ServiceExecutionRecoveryJob"}) {
            var reader = metadata.getMetadataReader(new FileSystemResource(root.resolve(
                    "backend/shop-core/target/classes/ai/neargo/shop/trade/" + type.replace('.', '/') + ".class")));
            var annotation = reader.getAnnotationMetadata().getAnnotationAttributes("org.springframework.stereotype.Component");
            assertThat(annotation).isNotNull();
            String explicit = (String) annotation.get("value");
            names.add(explicit.isBlank() ? Introspector.decapitalize(type.substring(type.lastIndexOf('.') + 1)) : explicit);
        }
        names.addAll(beautyBeanNames(root.resolve(
                "backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/"
                        + "BeautySourceConfiguration.java")));
        assertThat(names).doesNotHaveDuplicates();
    }

    private static List<String> beautyBeanNames(Path source) throws Exception {
        assertThat(source).isRegularFile();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Bean declaration guard requires the build JDK").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var names = new ArrayList<String>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null,
                    files.getJavaFileObjects(source));
            for (var unit : task.parse()) for (var declaration : unit.getTypeDecls()) {
                if (!(declaration instanceof ClassTree owner)
                        || !owner.getSimpleName().contentEquals("BeautySourceConfiguration")) continue;
                for (var member : owner.getMembers()) {
                    if (!(member instanceof MethodTree method)) continue;
                    var bean = annotation(method, "Bean");
                    if (bean == null) continue;
                    if (method.getName().toString().startsWith("beautyServiceExecutionRecovery")) {
                        var profile = annotation(method, "Profile");
                        assertThat(profile).isNotNull();
                        assertThat(values(profile, "value")).containsExactly("api");
                    }
                    var explicit = values(bean, "name", "value");
                    names.addAll(explicit.isEmpty() ? List.of(method.getName().toString()) : explicit);
                }
            }
        }
        assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList())
                .as("Beauty configuration must parse without its optional compiled dependency").isEmpty();
        assertThat(names).as("Actual Beauty configuration must declare beans").isNotEmpty();
        return names;
    }

    private static AnnotationTree annotation(MethodTree method, String name) {
        return method.getModifiers().getAnnotations().stream().filter(a -> {
            String type = a.getAnnotationType().toString();
            return type.equals(name) || type.equals("org.springframework.context.annotation." + name);
        }).findFirst().orElse(null);
    }

    private static List<String> values(AnnotationTree annotation, String... attributes) {
        var names = new ArrayList<String>();
        for (var argument : annotation.getArguments()) {
            String key = argument instanceof AssignmentTree assignment ? assignment.getVariable().toString() : "value";
            if (!List.of(attributes).contains(key)) continue;
            addStrings(argument instanceof AssignmentTree assignment ? assignment.getExpression() : argument, names);
        }
        return names;
    }

    private static void addStrings(ExpressionTree value, List<String> names) {
        if (value instanceof LiteralTree literal && literal.getValue() instanceof String name) names.add(name);
        else if (value instanceof NewArrayTree array) array.getInitializers().forEach(item -> addStrings(item, names));
        else throw new AssertionError("Bean/profile name must be an explicit string literal: " + value);
    }
}
