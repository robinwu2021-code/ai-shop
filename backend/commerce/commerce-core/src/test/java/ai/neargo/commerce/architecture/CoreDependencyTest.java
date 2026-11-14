package ai.neargo.commerce.architecture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class CoreDependencyTest {
    @Test
    void productionBytecodeUsesOnlyAllowedDependenciesAndAcyclicCoreFeatures() throws Exception {
        var root = Path.of("..").toAbsolutePath().normalize();
        var api = root.resolve("commerce-api/target/classes");
        assertTrue(Files.isDirectory(api), "API production bytecode must exist");
        var inputs = new java.util.ArrayList<String>();
        inputs.add(api.toString());
        var core = root.resolve("commerce-core/target/classes");
        if (Files.isDirectory(core)) inputs.add(core.toString());
        var features = new TreeMap<String, Set<String>>();
        int references = 0;
        for (var input : inputs) {
            var command =
                    java.util.List.of(
                            Path.of(System.getProperty("java.home"), "bin/jdeps").toString(),
                            "-verbose:class",
                            "-filter:none",
                            "--module-path",
                            api.toString(),
                            input);
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
            for (var line :
                    output.lines().filter(s -> s.startsWith(" ") && s.contains(" -> ")).toList()) {
                references++;
                String[] edge = line.trim().split("\\s+->\\s+");
                String target = edge[1].split("\\s+")[0];
                String left = feature(edge[0]), right = feature(target);
                if (left != null && right != null && !left.equals(right))
                    features.computeIfAbsent(left, ignored -> new TreeSet<>()).add(right);
                assertTrue(
                        target.startsWith("java.")
                                || target.startsWith("ai.neargo.commerce.api.")
                                || (input.contains("commerce-core")
                                        && target.startsWith("ai.neargo.commerce.core.")),
                        line);
            }
        }
        assertTrue(
                features.containsKey("subscription"),
                "jdeps must inspect the subscription feature");
        features.keySet()
                .forEach(
                        start -> {
                            var seen = new HashSet<String>();
                            var pending = new ArrayDeque<>(features.get(start));
                            while (!pending.isEmpty()) {
                                String next = pending.remove();
                                assertNotEquals(
                                        start,
                                        next,
                                        "cycle in core feature bytecode dependencies: " + features);
                                if (seen.add(next))
                                    pending.addAll(features.getOrDefault(next, Set.of()));
                            }
                        });
        assertTrue(references > 0, "jdeps must inspect real production class references");
        try (var files = Files.walk(api)) {
            assertTrue(
                    files.filter(
                                            p ->
                                                    p.toString().endsWith(".class")
                                                            && !p.getFileName()
                                                                    .toString()
                                                                    .equals("module-info.class"))
                                    .count()
                            > 0,
                    "API may not be empty");
        }
    }

    private static String feature(String className) {
        String prefix = "ai.neargo.commerce.core.";
        if (!className.startsWith(prefix)) return null;
        String name = className.substring(prefix.length());
        return name.substring(0, name.indexOf('.'));
    }

    @Test
    void runtimeDependencyGraphHasOnlyApi() throws Exception {
        var classpath = Files.readString(Path.of("target/runtime-classpath.txt")).trim();
        var entries = classpath.split(java.io.File.pathSeparator);
        assertEquals(1, entries.length, classpath);
        assertTrue(
                entries[0].endsWith("commerce-api/target/classes")
                        || entries[0].endsWith("commerce-api-0.1.0-SNAPSHOT.jar"),
                classpath);
        assertTrue(
                Files.readString(Path.of("../commerce-api/target/runtime-classpath.txt"))
                        .isBlank());
    }
}
