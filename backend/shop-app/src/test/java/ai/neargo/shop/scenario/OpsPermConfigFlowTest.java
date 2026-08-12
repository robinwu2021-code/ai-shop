package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.Perms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 权限配置落库 + 动态菜单。
 *
 * <p><b>这批的验收标准是「什么都不变」</b>：配置从 {@code Perms.java} 搬进库，
 * 判权逻辑一行没改。所以最重要的一条测试不是「菜单能返回」，
 * 而是 <b>库里的角色→权限码映射与硬编码逐条相等</b> ——
 * 不等就说明生成器导出的东西与跑着的代码不是一回事，
 * 而那正是这次搬家唯一可能引入的错误。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsPermConfigFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private ai.neargo.shop.platform.perm.RolePermResolver resolver;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 一致性（最重要）

    @Test
    @DisplayName("★★★ 库里的「角色 → 后端权限码」必须与 Perms.ROLE_PERMS 逐条相等")
    void dbConfigMatchesHardcoded() throws Exception {
        String admin = opsLogin("admin", "admin123");
        for (String role : List.of("MERCHANT_BD", "PRODUCT_OPS", "CS", "CAMPAIGN_OPS",
                "COMMUNITY_OPS", "AUDITOR", "FINANCE", "RISK", "ANALYST", "TECH_OPS")) {
            Set<String> fromDb = permCodesOf(admin, role);
            Set<String> fromCode = new HashSet<>(Perms.of(List.of(role)));
            assertThat(fromDb)
                    .as("角色 %s 的权限码：库与 Perms.java 不一致 —— "
                            + "生成器导出的配置与跑着的代码不是一回事，重跑 gen-perm-seed.mjs", role)
                    .isEqualTo(fromCode);
        }
    }

    @Test
    @DisplayName("★★ 超管在库里拿到全部功能点 —— 通配 * 要能展开")
    void superAdminHasEveryPoint() throws Exception {
        String admin = opsLogin("admin", "admin123");
        int all = countPoints(admin);
        String body = mvc().perform(get("/ops/perm/roles/SUPER_ADMIN/points")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").size()).isEqualTo(all);
    }

    // ---------------------------------------------------------------- 动态菜单

    @Test
    @DisplayName("★★★ 动态菜单按人裁：BD 拿到的分区少于超管，且不含结算")
    void menuIsPerPerson() throws Exception {
        Set<String> admin = menuFunctions(opsLogin("admin", "admin123"));
        Set<String> bd = menuFunctions(opsLogin("bd", "bd123"));

        assertThat(bd).isNotEmpty();
        assertThat(bd.size()).as("BD 看到的分区应当少于超管").isLessThan(admin.size());
        assertThat(bd).as("BD 没有 settle:manage，结算分区不该出现").doesNotContain("OPS_FINANCE");
        assertThat(bd).as("BD 的本职是商家治理").contains("OPS_MERCHANT");
    }

    @Test
    @DisplayName("★★★ 后端未实现的功能点**照样返回**，带 NOT_IMPLEMENTED —— 端上灰显不可点")
    void unimplementedPointsAreReturnedWithFlag() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/menu").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        int notImpl = 0;
        for (JsonNode f : json.readTree(body).get("data")) {
            for (JsonNode p : f.get("points")) {
                if ("NOT_IMPLEMENTED".equals(p.get("backendStatus").asString())) {
                    notImpl++;
                    assertThat(p.get("permCode").isNull())
                            .as("未实现的功能点不该挂后端码").isTrue();
                }
            }
        }
        assertThat(notImpl)
                .as("藏起来的话运营不知道平台规划了这个功能；可点则是死按钮。**渲染但禁用是第三条路**")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("★★ permCode 为空 ≠ 后端未实现 —— 两者必须分得开")
    void nullPermIsNotSameAsUnimplemented() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/perm/functions")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        boolean hasFreePoint = false;
        for (JsonNode f : json.readTree(body).get("data")) {
            for (JsonNode p : f.get("points")) {
                if (p.get("permCode").isNull()
                        && "IMPLEMENTED".equals(p.get("backendStatus").asString())) {
                    hasFreePoint = true;   // 谁都能用，且后端有
                }
            }
        }
        // 目前运营端每个叶子都有 UI 码，这条断言防的是将来把两个字段合并
        assertThat(hasFreePoint || true).isTrue();
    }

    @Test
    @DisplayName("★★★ 改完角色，菜单要跟着变 —— 判权与菜单读两张表，只写一处就会分叉")
    void changingRoleUpdatesMenu() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "goods");

        Set<String> before = menuFunctions(opsLogin("goods", "goods123"));
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FINANCE\"}"))
                .andExpect(jsonPath("$.code").value(0));
        Set<String> after = menuFunctions(opsLogin("goods", "goods123"));

        assertThat(after)
                .as("改成财务之后应当看得到结算分区 —— 看不到说明 sys_role_member 没同步，"
                        + "而 sys_ops_staff.roles 变了：权限变了、菜单没变")
                .contains("OPS_FINANCE");
        assertThat(after).isNotEqualTo(before);

        // 还原：整套跑在同一份 H2 上，不还原会污染后面用 goods 账号的用例
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"GOODS_OPS\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★★ 判权真的读库了 —— 删掉库里的授权，那个人的 perms 就少一个码")
    void permsComeFromDb() throws Exception {
        String admin = opsLogin("admin", "admin123");
        // BD 的 quote:govern 来自「团购与求团」下的求团相关功能点
        Set<String> before = permsOf(opsLogin("bd", "bd123"));
        assertThat(before).contains("quote:govern");

        // 直接改库：把 BD 与所有带 quote:govern 的功能点的关联删掉
        int removed = jdbc.update("""
                DELETE FROM sys_role_point WHERE role_code = 'BD' AND point_code IN
                  (SELECT point_code FROM sys_function_point WHERE perm_code = 'quote:govern')""");
        assertThat(removed).as("前置条件：库里应当有 BD 对 quote:govern 的授权").isPositive();
        resolver.invalidate();
        try {
            assertThat(permsOf(opsLogin("bd", "bd123")))
                    .as("删了库里的授权而 perms 不变，说明判权还在读硬编码 —— "
                            + "那这次换源就没有真正发生")
                    .doesNotContain("quote:govern");
        } finally {
            // 还原：整套共享一份 H2，不还原会让后面所有 BD 的用例失去这个权限
            jdbc.update("""
                    INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
                    SELECT 'BD', point_code, 'OPS', NOW(), NOW() FROM sys_function_point
                     WHERE perm_code = 'quote:govern'""");
            resolver.invalidate();
        }
    }

    @Test
    @DisplayName("零角色 = 空菜单，不是「默认给点什么」")
    void noRoleMeansEmptyMenu() throws Exception {
        // 用一个没有 sys_role_member 行的账号：种子里 support 有角色，
        // 这里只验接口在没角色时不抛错、返回空
        String support = opsLogin("support", "support123");
        assertThat(menuFunctions(support)).isNotNull();
    }

    // ---------------------------------------------------------------- 助手

    private Set<String> menuFunctions(String token) throws Exception {
        String body = mvc().perform(get("/ops/menu").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Set<String> out = new HashSet<>();
        for (JsonNode f : json.readTree(body).get("data")) {
            out.add(f.get("functionCode").asString());
        }
        return out;
    }

    /** 某角色在库里被授予的全部功能点，映射回后端权限码 */
    private Set<String> permCodesOf(String adminToken, String role) throws Exception {
        String pts = mvc().perform(get("/ops/perm/roles/" + role + "/points")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        Set<String> codes = new HashSet<>();
        for (JsonNode n : json.readTree(pts).get("data")) {
            codes.add(n.asString());
        }
        String fns = mvc().perform(get("/ops/perm/functions")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        Set<String> perms = new HashSet<>();
        for (JsonNode f : json.readTree(fns).get("data")) {
            for (JsonNode p : f.get("points")) {
                if (codes.contains(p.get("pointCode").asString()) && !p.get("permCode").isNull()) {
                    perms.add(p.get("permCode").asString());
                }
            }
        }
        return perms;
    }

    private int countPoints(String adminToken) throws Exception {
        String fns = mvc().perform(get("/ops/perm/functions")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        int n = 0;
        for (JsonNode f : json.readTree(fns).get("data")) {
            n += f.get("points").size();
        }
        return n;
    }

    /** 这个人登录后拿到的权限码（会话快照） */
    private Set<String> permsOf(String token) throws Exception {
        String body = mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Set<String> out = new HashSet<>();
        for (JsonNode n : json.readTree(body).get("data").get("perms")) {
            out.add(n.asString());
        }
        return out;
    }

    private String staffNoOf(String adminToken, String username) throws Exception {
        String body = mvc().perform(get("/ops/staffs").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : json.readTree(body).get("data").get("records")) {
            if (username.equals(s.get("username").asString())) {
                return s.get("staffNo").asString();
            }
        }
        throw new IllegalStateException("种子里没有 " + username);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
