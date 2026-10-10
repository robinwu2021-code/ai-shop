package ai.neargo.shop.arch;

import ai.neargo.shop.auth.BizPerms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库里的**预置角色**必须与 {@link BizPerms#ROLE_PERMS} 逐条相同。
 *
 * <p><b>为什么这条守卫必须存在</b>：V71 之后判权读的是 {@code mch_role}，
 * 而「这个角色能做什么」的说明、两份矩阵产物、以及 {@code BizPermsTest} 的 9 条断言
 * 读的都还是 {@code BizPerms}。两边一旦分叉，症状是
 * <b>「界面上写着店长能改库存，实际打不通」</b> —— 而两边各自看都对。
 *
 * <p>分叉最可能发生在「加一个权限码」的时候：改了 Java 常量表，忘了改迁移里的那行 INSERT。
 * 那一刻没有任何东西会报错。
 */
class BizRoleSeedTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V71__mch_role.sql");

    /** `('*', 'MANAGER', '店长', '["biz:receive",...]', 1, ...` */
    private static final Pattern SEED = Pattern.compile(
            "\\('\\*',\\s*'([A-Z_]+)',\\s*'[^']*',\\s*'(\\[[^\\]]*\\])'");

    @Test
    @DisplayName("★★★ 迁移里的预置角色 = BizPerms.ROLE_PERMS —— 加权限码时最容易漏的一处")
    void seedMatchesBizPerms() throws IOException {
        Map<String, List<String>> seeded = parseSeed();
        Map<String, Set<String>> expected = expectedFromCode();

        assertThat(seeded.keySet())
                .as("迁移里的预置角色码与 BizPerms 的角色常量对不上")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        for (var e : expected.entrySet()) {
            String role = e.getKey();
            assertThat(new TreeSet<>(seeded.get(role)))
                    .as("角色 %s 的权限码：迁移与 BizPerms 不一致。\n"
                            + "  改了 BizPerms.ROLE_PERMS 就要改 V71 的那行 INSERT ——\n"
                            + "  漏改的后果是「界面说他能做，实际打不通」，而两边各自看都对", role)
                    .isEqualTo(new TreeSet<>(e.getValue()));
        }
    }

    @Test
    @DisplayName("★★ 预置角色之外没有别的 builtin —— 自定义角色不许伪装成预置")
    void onlySixBuiltinRoles() throws IOException {
        assertThat(parseSeed()).hasSize(6);
    }

    @Test
    @DisplayName("★★★ 只有 OWNER 能带通配 —— 其余预置角色都是显式清单")
    void onlyOwnerHasWildcard() throws IOException {
        Map<String, List<String>> seeded = parseSeed();
        for (var e : seeded.entrySet()) {
            if (e.getValue().contains("*")) {
                assertThat(e.getKey())
                        .as("通配只属于 OWNER。别的角色带上它，等于绕过整张权限表")
                        .isEqualTo(BizPerms.OWNER);
            }
        }
    }

    // ---------------------------------------------------------------- 两侧解析

    private static Map<String, List<String>> parseSeed() throws IOException {
        String sql = Files.readString(MIGRATION);
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = SEED.matcher(sql);
        while (m.find()) {
            List<String> perms = java.util.Arrays.stream(
                            m.group(2).replaceAll("[\\[\\]\"]", "").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            out.put(m.group(1), perms);
        }
        assertThat(out).as("一条预置角色都没解析到 —— 正则或迁移文件变了？").isNotEmpty();
        return out;
    }

    /**
     * 从 {@code BizPerms} 反射出角色 → 权限码。
     *
     * <p>{@code ROLE_PERMS} 是私有的，这里刻意用反射而不是给它开个 getter ——
     * 开 getter 会让「判权表」变成一个可被业务代码读的东西，
     * 而它只该被 {@code can()} 用。守卫是唯一的例外，例外就该长得像例外。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> expectedFromCode() {
        try {
            var f = BizPerms.class.getDeclaredField("ROLE_PERMS");
            f.setAccessible(true);
            Map<String, List<String>> raw = (Map<String, List<String>>) f.get(null);
            Map<String, Set<String>> out = new LinkedHashMap<>();
            raw.forEach((role, perms) -> out.put(role, new TreeSet<>(perms)));
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读不到 BizPerms.ROLE_PERMS", e);
        }
    }
}
