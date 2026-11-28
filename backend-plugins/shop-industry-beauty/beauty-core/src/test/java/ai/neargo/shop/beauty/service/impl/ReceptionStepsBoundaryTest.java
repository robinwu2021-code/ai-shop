package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 接待操作只有一个写入口（TDD-美业域迁移新库 §2.2.1，用户原则「一个改动要调多处说明设计不好」）：
 * 除了 {@link ReceptionSteps}，任何类都不能对接待操作的 Mapper 做写入或加锁，只能读。
 * 这样「在哪个库开事务、先后顺序、断了怎么补」只在一处，换库或调整顺序只改那一处。
 */
class ReceptionStepsBoundaryTest {
    private static final Path SOURCES = Path.of("src/main/java/ai/neargo/shop/beauty");
    private static final Pattern FIELD = Pattern.compile("BeautyMappers\\.ReceptionOperationMapper\\s+(\\w+)");
    private static final String WRITES = "insert|update\\w*|selectScopedForUpdate|claim\\w*|discard\\w*|delete\\w*";

    @Test
    void onlyReceptionStepsWritesOrLocksTheReceptionOperation() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("ReceptionSteps.java") || file.toString().contains("/mapper/")) continue;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                var fields = FIELD.matcher(source);
                while (fields.find()) {
                    var calls = Pattern.compile("\\b" + fields.group(1) + "\\.(" + WRITES + ")\\(").matcher(source);
                    while (calls.find()) {
                        int line = source.substring(0, calls.start()).split("\n", -1).length;
                        violations.add(name + ":" + line + " " + calls.group());
                    }
                }
            }
        }
        assertThat(violations).as("接待操作的写入与加锁只能经 ReceptionSteps").isEmpty();
    }

    @Test
    void theScanSeesTheMapperFields() throws IOException {
        // 防止扫描本身失效（路径或写法变了就什么都扫不到）
        String coordinator = Files.readString(SOURCES.resolve("service/impl/ReceptionCoordinator.java"),
                StandardCharsets.UTF_8);
        assertThat(FIELD.matcher(coordinator).find()).isTrue();
    }
}
