package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code "BRAND"} 是连锁（GROUP）的历史存储编码，只许经 {@code ScopeCodes} 翻译（连锁整体设计 §4.1）。
 *
 * <p>生产代码里每个文件的 {@code "BRAND"} 字面量个数不得超过基线 {@code brand-code-literal-baseline.txt}；
 * 收敛掉的要从基线删掉（只许减少）。{@code ScopeCodes} 本身不计。重写基线：{@code -Dbrand.literal.record=true}。
 */
@DisplayName("架构 · 连锁编码 BRAND 只在 ScopeCodes 翻译")
class BrandCodeLiteralTest {
    private static final Path ROOT = Paths.get(System.getProperty("user.dir")).getParent().getParent();
    private static final Path BASELINE = ROOT.resolve("backend/shop-app/src/test/resources/brand-code-literal-baseline.txt");
    private static final Pattern LITERAL = Pattern.compile("\"BRAND\"");
    private static final String TRANSLATOR = "backend/shop-base/src/main/java/ai/neargo/shop/spi/org/ScopeCodes.java";

    @Test
    void brandLiteralOnlyShrinks() throws IOException {
        Map<String, Integer> actual = scan();
        if (Boolean.getBoolean("brand.literal.record")) {
            List<String> lines = new ArrayList<>();
            actual.forEach((file, n) -> lines.add(file + " " + n));
            Files.write(BASELINE, lines, StandardCharsets.UTF_8);
            return;
        }
        Map<String, Integer> baseline = new TreeMap<>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                int sp = line.lastIndexOf(' ');
                baseline.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1)));
            }
        }
        List<String> grown = new ArrayList<>();
        actual.forEach((file, n) -> {
            if (n > baseline.getOrDefault(file, 0)) {
                grown.add(file + " " + baseline.getOrDefault(file, 0) + " → " + n);
            }
        });
        List<String> stale = new ArrayList<>();
        baseline.forEach((file, n) -> {
            if (actual.getOrDefault(file, 0) < n) {
                stale.add(file + " 基线 " + n + "，实际 " + actual.getOrDefault(file, 0));
            }
        });
        assertThat(grown).as("新增的 \"BRAND\" 字面量：改用 ScopeCodes").isEmpty();
        assertThat(stale).as("已经收敛的文件请同步减少基线（brand-code-literal-baseline.txt）").isEmpty();
    }

    private static Map<String, Integer> scan() throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        for (String top : List.of("backend", "backend-plugins")) {
            Path base = ROOT.resolve(top);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")
                        && p.toString().contains("/src/main/java/") && !p.toString().contains("/target/")).toList()) {
                    String rel = ROOT.relativize(f).toString().replace('\\', '/');
                    if (rel.equals(TRANSLATOR)) {
                        continue;
                    }
                    Matcher m = LITERAL.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    int n = 0;
                    while (m.find()) {
                        n++;
                    }
                    if (n > 0) {
                        out.put(rel, n);
                    }
                }
            }
        }
        return out;
    }
}
