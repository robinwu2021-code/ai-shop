package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 平台基础库的表只能经平台句柄访问（TDD-平台基础库换表 §2 第 1 步）。
 *
 * <p>平台的代码改成新表、绑到新库之后，凡是不经平台句柄直接查平台表的地方都会去主库读旧表 ——
 * 不报错，读到的是另一份数据。所以换表前要把这些地方清掉。这条守卫列出它们：不在平台句柄下的代码里，
 * 手写 SQL 提到平台表，或者直接注入平台的 Mapper。结果与 {@code backend/known-platform-table-access.txt}
 * 比，只准变短。
 *
 * <p>「在平台句柄下」：商户核心（{@code shop-merchant} 去掉拆出去的子域包）、C 端账号包、会话存储两个模块、
 * 网关的商户桥接包。
 */
class PlatformTableAccessTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path KNOWN = BACKEND.resolve("known-platform-table-access.txt");

    private static final List<String> PLATFORM_CODE = List.of(
            "shop-merchant/src/main/java/ai/neargo/shop/merchant/", "shop-core/src/main/java/ai/neargo/shop/user/",
            "shop-auth-store/", "shop-base-auth/", "shop-gateway/src/main/java/ai/neargo/shop/merchantbridge/");
    /**
     * 提到平台表名但不是访问：数据域按表名登记（拦截器对每个工厂都生效）；会话存储的表名配置（它本身就是平台句柄的一部分）；
     * 已执行的历史迁移（冻结，只在旧库上跑过）。
     */
    private static final List<String> NOT_ACCESS = List.of("shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java",
            "shop-app/src/main/java/ai/neargo/shop/config/SessionProfiles.java", "/src/main/java/db/migration/");

    private static final List<String> SPLIT_OUT = List.of("/merchant/booking/", "/merchant/funds/", "/merchant/plan/",
            "/merchant/paymerchant/", "/merchant/dictionary/", "/merchant/violation/");

    /** 旧库里属于平台基础库、在新库里改了名或并进别的表的旧表名。新库的表名取自建表语句。 */
    private static final Set<String> OLD_NAMES = Set.of("mch_channel_area", "mch_channel_pickup", "mch_entity_community",
            "mch_service_area", "mch_fulfillment_channel", "mch_personnel_account", "mch_store_role", "usr_mp_session",
            "usr_login_ticket", "mch_pos_pin_recovery", "mch_management_space", "mch_management_grant",
            "mch_operating_entity", "mch_entity_profile_projection", "mch_pos_staff_pin_policy", "sys_ops_staff",
            "mch_service_area_cell");

    private static final Pattern IDENT = Pattern.compile("\\b([a-z]+_[a-z_]+)\\b");
    private static final Pattern MARKED = Pattern.compile("interface\\s+(\\w+)\\s+extends\\s+(?:PlatformTableMapper|PlatformSqlMapper)\\b");

    @Test
    @DisplayName("平台基础库的表只能经平台句柄访问：越界清单只准变短")
    void platform_tables_are_reached_only_through_the_platform_handle() throws IOException {
        Set<String> tables = platformTables();
        Set<String> mappers = platformMappers();
        Set<String> actual = new TreeSet<>();
        for (Path file : sources()) {
            String path = BACKEND.relativize(file).toString();
            if (PLATFORM_CODE.stream().anyMatch(path::contains) && SPLIT_OUT.stream().noneMatch(path::contains)) {
                continue;
            }
            if (NOT_ACCESS.stream().anyMatch(path::contains)) {
                continue;
            }
            String code = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
            boolean hit = false;
            var literal = Pattern.compile("(?s)\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"").matcher(code);
            while (!hit && literal.find()) {
                var ident = IDENT.matcher(literal.group(1) != null ? literal.group(1) : literal.group(2));
                while (ident.find()) {
                    if (tables.contains(ident.group(1))) {
                        hit = true;
                        break;
                    }
                }
            }
            for (String mapper : mappers) {
                if (!hit && code.matches("(?s).*\\b" + mapper + "\\b.*")) {
                    hit = true;
                }
            }
            if (hit) {
                actual.add(path);
            }
        }
        assertThat(actual).as("一个越界都没扫到 —— 扫描坏了？").isNotEmpty();

        Set<String> known = new TreeSet<>();
        for (String line : Files.readAllLines(KNOWN, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                known.add(line.trim());
            }
        }
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(known);
        Set<String> gone = new TreeSet<>(known);
        gone.removeAll(actual);
        assertThat(added).as("新的越界：这些代码不在平台句柄下，却直接访问平台基础库的表：\n  %s",
                String.join("\n  ", added)).isEmpty();
        assertThat(gone).as("这些已经不越界了，从 known-platform-table-access.txt 删掉：\n  %s", String.join("\n  ", gone))
                .isEmpty();
    }

    private static List<Path> sources() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        try (Stream<Path> top = Files.walk(BACKEND, 6)) {
            top.filter(p -> p.endsWith(Path.of("src", "main", "java"))).forEach(roots::add);
        }
        Path plugins = BACKEND.resolveSibling("backend-plugins");
        if (Files.isDirectory(plugins)) {
            try (Stream<Path> top = Files.walk(plugins, 6)) {
                top.filter(p -> p.endsWith(Path.of("src", "main", "java"))).forEach(roots::add);
            }
        }
        List<Path> files = new java.util.ArrayList<>();
        for (Path root : roots) {
            if (root.toString().contains("/target/")) {
                continue;
            }
            try (Stream<Path> all = Files.walk(root)) {
                all.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }

    private static Set<String> platformTables() throws IOException {
        Set<String> tables = new TreeSet<>(OLD_NAMES);
        var create = Pattern.compile("CREATE TABLE `(\\w+)`").matcher(Files.readString(
                BACKEND.resolve("shop-app/src/test/resources/db/n_platform/V1__platform_design.sql"),
                StandardCharsets.UTF_8));
        while (create.find()) {
            tables.add(create.group(1));
        }
        return tables;
    }

    private static Set<String> platformMappers() throws IOException {
        Set<String> mappers = new TreeSet<>();
        for (Path file : sources()) {
            String path = file.toString();
            if (!path.contains("/merchant/mapper/") && !path.contains("/user/mapper/")) {
                continue;
            }
            var m = MARKED.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                mappers.add(m.group(1));
            }
        }
        return mappers;
    }
}
