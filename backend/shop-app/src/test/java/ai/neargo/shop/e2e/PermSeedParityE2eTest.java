package ai.neargo.shop.e2e;

import ai.neargo.shop.auth.Perms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限种子的一致性 —— **对着真正跑完的迁移验，不是对着测试夹具**。
 *
 * <h3>为什么必须在 e2e 里做</h3>
 * 默认构建里那条同名守卫（{@code OpsPermConfigFlowTest.dbConfigMatchesHardcoded}）
 * 跑在 H2 上，而 {@code application-h2db.yml} 里 <b>{@code flyway.enabled: false}</b> ——
 * 它验的是 {@code schema-test.sql} 自带的种子，<b>不是 {@code db/migration/V*.sql}
 * 跑完的结果</b>。两者可以任意漂移而无人知晓。
 *
 * <p>2026-08-12 就漂过一次：V72 按当时的 UI_PERM_MAP 重建功能点，而
 * {@code Perms.ROLE_PERMS} 里有 22 个映射不出来的后端码 —— 角色→功能点是靠
 * {@code perm_code} 对上的，没有功能点带那个码，授权就<b>无处安放、静默消失</b>。
 * 9 个角色受影响，FINANCE 丢 9/16，<b>没有一条测试变红</b>，是人工查库才发现的。
 *
 * <h3>为什么不在内存里回放迁移</h3>
 * 想过。数了一下实际语句：授权重映射有三处是
 * {@code INSERT … SELECT … JOIN … WHERE}（还用了 MySQL 的 null-safe 等号 {@code <=>}）。
 * 要回放它们就是写半个 SQL 引擎，而<b>一个写错的回放器比没有守卫更坏</b> ——
 * 它会给出看着权威、实则错误的结论。而那种写法还会继续出现：
 * 按 href / perm_code 重新推导授权，正是「不写死角色名单」的正确写法。
 *
 * <p>所以让真数据库去执行，这里只查结果。
 *
 * <h3>本类不打 HTTP</h3>
 * 它验的是**数据**，不是接口。继承 {@link E2eBase} 只为复用 clean → migrate → 播种。
 */
@DisplayName("权限种子：迁移跑完的结果必须与 Perms.ROLE_PERMS 一致")
class PermSeedParityE2eTest extends E2eBase {

    /** 通配角色不逐码展开：它的语义是「全部」，展开会漏掉将来新加的码。 */
    private static final String WILDCARD = "*";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        resetDatabaseOnce();
    }

    // ---------------------------------------------------------------- 核心

    @Test
    @DisplayName("★★★ 每个角色的权限码与 Perms.ROLE_PERMS 逐条相等")
    void grantsMatchCode() {
        Map<String, Set<String>> fromDb = grantsFromDb();
        List<String> problems = new java.util.ArrayList<>();

        grantsFromCode().forEach((role, want) -> {
            if (want.contains(WILDCARD)) {
                return;   // 通配角色由 wildcardRoleHasEveryPoint 单独验
            }
            Set<String> got = fromDb.getOrDefault(role, Set.of());
            Set<String> missing = new LinkedHashSet<>(want);
            missing.removeAll(got);
            Set<String> extra = new LinkedHashSet<>(got);
            extra.removeAll(want);
            if (!missing.isEmpty() || !extra.isEmpty()) {
                problems.add("%s 少了 %s；多了 %s".formatted(role,
                        missing.isEmpty() ? "-" : missing, extra.isEmpty() ? "-" : extra));
            }
        });

        assertThat(problems)
                .as("""
                        迁移跑完之后，库里的角色→权限码与 Perms.ROLE_PERMS 不一致。

                        **少掉的那一侧尤其危险**：它没有任何症状，那个角色只是调某个接口
                        突然 403。2026-08-12 的 V72 就是这样丢了 22 个码。

                        多半是新加/改了迁移之后没重跑 ops-web/scripts/gen-perm-seed.mjs，
                        或者 Perms.ROLE_PERMS 用到了 UI_PERM_MAP 映射不出来的后端码
                        （生成器末段有「按后端码兜底」那一块，看看是不是被绕过了）。
                        """)
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ ROLE_PERMS 用到的每个后端码，库里都要有功能点承载它")
    void everyCodeHasACarryingPoint() {
        /*
         * 这条比上一条**更早变红**，且错误信息直指根因。
         * 上一条说的是「FINANCE 少了 9 个码」，这一条说的是
         * 「merchant:admission:read 没有任何功能点带它」—— 后者才指向要改的地方。
         */
        Set<String> carried = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT perm_code FROM sys_function_point WHERE perm_code IS NOT NULL",
                String.class));
        Set<String> used = new LinkedHashSet<>();
        grantsFromCode().values().forEach(used::addAll);
        used.remove(WILDCARD);
        used.removeAll(carried);

        assertThat(used)
                .as("""
                        这些后端码在 Perms.ROLE_PERMS 里被用到，但库里**没有任何功能点带它**。
                        角色→功能点是靠 perm_code 对上的 —— 没有承载它的功能点，
                        那条授权就无处安放，重新生成种子时会静默消失。
                        """)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 通配角色持有全部功能点 —— * 要能真的展开")
    void wildcardRoleHasEveryPoint() {
        List<String> wildcardRoles = jdbc.queryForList(
                "SELECT role_code FROM sys_role WHERE end_code='OPS' AND wildcard=1", String.class);
        assertThat(wildcardRoles).as("前置条件：库里应当有通配角色").isNotEmpty();

        Integer all = jdbc.queryForObject("SELECT COUNT(*) FROM sys_function_point", Integer.class);
        for (String role : wildcardRoles) {
            Integer mine = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT point_code) FROM sys_role_point WHERE role_code=? AND end_code='OPS'",
                    Integer.class, role);
            assertThat(mine).as("通配角色 %s 应当持有全部 %d 个功能点", role, all).isEqualTo(all);
        }
    }

    @Test
    @DisplayName("★★ (role_code, point_code) 不能重复 —— 唯一索引在这张表上拦不住")
    void noDuplicateGrants() {
        /*
         * uk_role_point 是 (role_code, point_code, entity_no)，而 entity_no 是 NULL，
         * **MySQL 的唯一索引不去重 NULL** —— INSERT IGNORE 在这张表上是无效护栏。
         * V72 第一版因此插出 373 行（重复 169 行），靠人工数出来的。
         */
        List<Map<String, Object>> dups = jdbc.queryForList("""
                SELECT role_code, point_code, COUNT(*) n FROM sys_role_point
                 WHERE end_code='OPS' GROUP BY role_code, point_code HAVING n > 1""");
        assertThat(dups)
                .as("授权有重复行。uk_role_point 含 entity_no 且它是 NULL，索引挡不住 —— "
                        + "迁移里不能靠 INSERT IGNORE 去重，要靠「两步互不重叠」或 NOT EXISTS")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 每个菜单功能点的 href 都要在 nav.ts 里存在 —— 否则是个 404 入口")
    void menuHrefsExistInNav() {
        Set<String> navHrefs = navHrefsFromSource();
        assertThat(navHrefs).as("前置条件：nav.ts 里应当解析出叶子").isNotEmpty();

        List<String> orphans = jdbc.queryForList(
                        "SELECT href FROM sys_function_point WHERE point_type='MENU' AND href IS NOT NULL",
                        String.class).stream()
                .filter(h -> !navHrefs.contains(h))
                .toList();

        assertThat(orphans)
                .as("库里这些菜单项在 ops-web/lib/nav.ts 里没有对应叶子 —— "
                        + "它们会渲染成菜单入口，点进去是 404")
                .isEmpty();
    }

    // ---------------------------------------------------------------- 取数

    /** 库里的「角色 → 后端权限码」。只看内置角色：自建角色不在 Perms 里，本就不该被约束。 */
    private Map<String, Set<String>> grantsFromDb() {
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.query("""
                SELECT rp.role_code, fp.perm_code
                  FROM sys_role_point rp
                  JOIN sys_function_point fp ON fp.point_code = rp.point_code
                  JOIN sys_role r ON r.role_code = rp.role_code AND r.end_code = rp.end_code
                 WHERE rp.end_code = 'OPS' AND r.builtin = 1 AND fp.perm_code IS NOT NULL""",
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>())
                            .add(rs.getString(2));
                });
        return out;
    }

    /**
     * {@code Perms.ROLE_PERMS} 的解析结果。
     *
     * <p><b>读源码而不是反射</b>：反射拿到的是「跑起来是什么」，而守卫要守的是
     * 「源码里写了什么」—— 两者本该相等，正因如此才不能用其中一个去验另一个。
     * 与 {@code gen-perm-seed.mjs} / {@code BizEndpointPermTest} 同一手法。
     */
    private Map<String, Set<String>> grantsFromCode() {
        String src = readRepoFile("backend/shop-base/src/main/java/ai/neargo/shop/auth/Perms.java");
        Map<String, String> consts = new HashMap<>();
        Matcher c = Pattern.compile("String\\s+([A-Z_]+)\\s*=\\s*\"([^\"]+)\"").matcher(src);
        while (c.find()) {
            consts.put(c.group(1), c.group(2));
        }
        int at = src.indexOf("ROLE_PERMS = Map.");
        // 解析失效就直接红：扫不到角色时静默返回空 map，上面每条断言都会假绿
        assertThat(at).as("在 Perms.java 里找不到 ROLE_PERMS —— 写法变了，这个解析已经查不到东西")
                .isGreaterThan(0);

        Map<String, Set<String>> out = new HashMap<>();
        Matcher e = Pattern.compile("Map\\.entry\\(\"([A-Z_]+)\",\\s*List\\.of\\(([\\s\\S]*?)\\)\\)")
                .matcher(src.substring(at));
        while (e.find()) {
            Set<String> codes = new LinkedHashSet<>();
            Matcher t = Pattern.compile("\"(\\*|[a-z][a-z:_-]*)\"|\\b([A-Z][A-Z_]+)\\b").matcher(e.group(2));
            while (t.find()) {
                if (t.group(1) != null) {
                    codes.add(t.group(1));
                } else if (consts.containsKey(t.group(2))) {
                    codes.add(consts.get(t.group(2)));
                }
            }
            out.put(e.group(1), codes);
        }
        assertThat(out).as("Perms.ROLE_PERMS 里一个角色都没解析出来 —— 解析失效了").isNotEmpty();
        return out;
    }

    /** ops-web/lib/nav.ts 里全部叶子的 href。 */
    private Set<String> navHrefsFromSource() {
        String src = readRepoFile("ops-web/lib/nav.ts");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\{\\s*href:\\s*\"([^\"]+)\"").matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        // section 自身的 href（叶子里不一定出现，如无子功能的看板）
        Matcher s = Pattern.compile("module:\\s*\"[^\"]+\",\\s*href:\\s*\"([^\"]+)\"").matcher(src);
        while (s.find()) {
            out.add(s.group(1));
        }
        return out;
    }

    /** 仓库根相对路径读文件。测试的工作目录是 shop-app，往上两级到仓库根。 */
    private String readRepoFile(String relative) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("ops-web/lib/nav.ts"))) {
            root = root.getParent();
        }
        assertThat(root).as("找不到仓库根（用 ops-web/lib/nav.ts 定位）").isNotNull();
        try {
            return Files.readString(root.resolve(relative));
        } catch (Exception ex) {
            throw new IllegalStateException("读不到 " + relative, ex);
        }
    }
}
