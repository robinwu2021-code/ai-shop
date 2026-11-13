package ai.neargo.shop.food.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 餐饮 Web 控制器不走包扫描，而是在 {@code config} 里逐个 {@code @Bean} 或 {@code @Import} 登记。
 * 新控制器忘了登记时，单元测试照绿、线上整组端点 404（柜台结账第一次在本机起服务才发现）。
 */
class FoodControllerRegistrationTest {
    private static final Path MAIN = Path.of("src/main/java/ai/neargo/shop/food");

    @Test
    void everyRestControllerIsRegisteredByAConfiguration() throws IOException {
        String configs;
        try (Stream<Path> files = Files.list(MAIN.resolve("config"))) {
            var all = new StringBuilder();
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(f)).append('\n');
            }
            // 条件注解里的 Xxx.class 不算登记（@ConditionalOnMissingBean(X.class) 本身不会注册 X）
            configs = all.toString().replaceAll("@ConditionalOn\\w+\\([^)]*\\)", "");
        }
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String dir : List.of("api/biz", "api/mp", "api/ops")) {
            Path d = MAIN.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (!Files.readString(f).contains("@RestController")) continue;
                    checked++;
                    String name = f.getFileName().toString().replace(".java", "");
                    boolean registered = Pattern.compile("(^|[^A-Za-z])" + name + "\\.class").matcher(configs).find()
                            || Pattern.compile("[^A-Za-z]" + name + " [a-zA-Z]+\\(").matcher(configs).find();
                    if (!registered) missing.add(name);
                }
            }
        }
        assertThat(checked).as("扫描面不能是空的").isGreaterThan(40);
        assertThat(missing).as("这些控制器没有在 food-web config 里 @Bean / @Import 登记，运行时不会注册").isEmpty();
    }
}
