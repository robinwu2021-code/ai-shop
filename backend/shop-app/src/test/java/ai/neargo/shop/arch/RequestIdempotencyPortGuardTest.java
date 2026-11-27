package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 接口级防重只经统一端口 {@code RequestIdempotencyPort}（TDD-基础能力库对齐 §2.2 1g，Q11 选 A）。
 *
 * <p>调用方依赖旧类 {@code IdempotencyService}（迁移期的转发器），回执表随基础能力换库时就得逐个改调用方；只依赖端口则一行不动。
 * 扫描后端与插件全部模块的 {@code src/main/java}，与 {@code backend/known-idempotency-service-refs.txt} 比，只准变短；
 * 名单清空时删掉转发器（实现是 {@code idem.port.RequestIdempotencyPortImpl}）。
 */
class RequestIdempotencyPortGuardTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path KNOWN = BACKEND.resolve("known-idempotency-service-refs.txt");
    private static final Pattern REF = Pattern.compile("\\bIdempotencyService\\b");

    @Test
    @DisplayName("调用方只依赖统一防重端口，存量只准变短")
    void callersDependOnThePortOnly() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Stream.concat(Files.walk(BACKEND), Files.walk(BACKEND.resolveSibling("backend-plugins")))) {
            files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(p -> {
                        if (REF.matcher(read(p)).find()) {
                            found.add(BACKEND.relativize(p).toString());
                        }
                    });
        }
        List<String> known = Files.readAllLines(KNOWN, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertThat(found).as("新出现的直接依赖：改成 RequestIdempotencyPort").isSubsetOf(known);
        assertThat(known).as("已迁走的请从 known-idempotency-service-refs.txt 删掉（只准变短）").isSubsetOf(found);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
