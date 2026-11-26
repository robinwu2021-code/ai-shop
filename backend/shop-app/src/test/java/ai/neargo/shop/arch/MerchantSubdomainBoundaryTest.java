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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 从商户域里拆出来的子域，别人只能走它的入口，不能直接用它的 Mapper。
 *
 * <p>商户域（shop-merchant）历史上把「商户名下的一切」都收了进来；新的库表设计把其中几块分到了别的库
 * （见 docs/technical/商户域设计方案评审.md）。拆出来的每一块有自己的包和一个入口接口。
 * 这条守卫盯的是「拆了又长回去」：有人图省事在商户核心里再注入一次子域的 Mapper，
 * 今天能跑（还在同一个库），到那一块真的换库那天才会断。
 */
class MerchantSubdomainBoundaryTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();

    /** 子域的包路径片段 → 包外不许出现的 Mapper 名。 */
    private static final Map<String, List<String>> SUBDOMAINS = Map.of(
            "/merchant/dictionary/", List.of("SysAuthCodeMapper", "SysStoreIndustryMapper"),
            "/merchant/violation/", List.of("ViolationMapper"),
            "/merchant/plan/", List.of("EntityPlanMapper", "PlanDefMapper"));

    /** 开发库造数直接写字典表，是有意的例外。 */
    private static final List<String> SEEDERS = List.of("DevSeeder.java");

    @Test
    @DisplayName("拆出去的子域只能经入口访问：包外不得引用它的 Mapper")
    void subdomain_mappers_stay_inside_their_package() throws IOException {
        List<String> leaks = new ArrayList<>();
        int scanned = 0;
        for (String module : List.of("shop-merchant", "shop-gateway", "shop-core", "shop-app")) {
            Path root = BACKEND.resolve(module).resolve("src/main/java");
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    if (SEEDERS.contains(file.getFileName().toString())) {
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    for (var subdomain : SUBDOMAINS.entrySet()) {
                        if (file.toString().contains(subdomain.getKey())) {
                            continue;
                        }
                        for (String mapper : subdomain.getValue()) {
                            if (source.matches("(?s).*\\b" + mapper + "\\b.*")) {
                                leaks.add(BACKEND.relativize(file) + " 用了 " + mapper);
                            }
                        }
                    }
                }
            }
        }
        assertThat(scanned).as("一个源文件都没扫到 —— 目录改名了？").isGreaterThan(1000);
        assertThat(leaks).as("子域的 Mapper 被包外的代码直接使用：\n  %s", String.join("\n  ", leaks)).isEmpty();
    }
}
