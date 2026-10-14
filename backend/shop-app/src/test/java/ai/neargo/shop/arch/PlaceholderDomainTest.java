package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 占位域名不许出现在**会发给用户的值**里。
 *
 * <p>这条守卫是被一个具体的错换来的：店铺码与分享素材两个端点，各自写死了
 * {@code "https://shop.example.com/s/" + code}。商家复制出去的链接、
 * 印在包装袋上的贴纸，全都指向一个不存在的地方 ——
 * 而 B-3.3「分享素材」与 B-11.12.6「店铺码」在功能清单上都标着「已实现」。
 *
 * <p><b>它不报错、不崩溃、测试也全绿</b>：生成得出物料，只是物料是废的。
 * 这类错的代价不在发现的那一刻，在发现之前 —— 商家印了几百张贴纸。
 *
 * <p>注释里可以提它（说明历史），**字符串字面量里不行**。
 */
class PlaceholderDomainTest {

    private static final List<String> PLACEHOLDERS =
            List.of("shop.example.com", "example.com/s/", "your-domain.com");

    @Test
    @DisplayName("★★ 占位域名不出现在字符串字面量里 —— 发出去的链接必须是真的")
    void noPlaceholderDomainInLiterals() throws IOException {
        Path root = Path.of("").toAbsolutePath().getParent();  // backend/ 的上一级
        List<String> offenders = new java.util.ArrayList<>();

        for (Path dir : List.of(root.resolve("backend"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("/target/"))
                        .filter(p -> !p.toString().endsWith("PlaceholderDomainTest.java"))
                        .forEach(p -> {
                            try {
                                int no = 0;
                                for (String line : Files.readAllLines(p)) {
                                    no++;
                                    String code = stripComment(line);
                                    for (String bad : PLACEHOLDERS) {
                                        if (code.contains("\"") && code.contains(bad)) {
                                            offenders.add(p.getFileName() + ":" + no + " → " + bad);
                                        }
                                    }
                                }
                            } catch (IOException ignored) {
                                // 读不了的文件跳过：这条守卫不该因为一个权限问题把构建拦下来
                            }
                        });
            }
        }

        assertThat(offenders)
                .as("这些地方把占位域名写进了字面量 —— 它会被当成真链接发给商家：\n  %s\n"
                        + "  改法：抽成配置（见 StoreLinkService），**没配就返回 null，不要发假的**。",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /** 去掉行注释部分 —— 注释里提占位域名是允许的（说明历史），字面量里不行 */
    private static String stripComment(String line) {
        String s = line.trim();
        if (s.startsWith("*") || s.startsWith("//") || s.startsWith("/*")) {
            return "";
        }
        int i = line.indexOf("//");
        return i >= 0 ? line.substring(0, i) : line;
    }
}
