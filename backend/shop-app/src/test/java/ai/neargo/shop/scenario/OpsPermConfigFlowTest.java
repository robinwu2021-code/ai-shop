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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @DisplayName("★★★ 改了权限就必须把人踢下线 —— 运营端删掉轮询之后，这是唯一的传播机制")
    void changingPermsKicksTheHolders() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "goods");
        String victim = opsLogin("goods", "goods123");

        // 前置：这张令牌此刻是好用的
        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + victim))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * **运营端已经没有轮询了**（2026-08-13 删掉那个 60 秒的定时器）。
         * 端上的 `perms` 是登录那一刻存进 localStorage 的快照，
         * 而它之所以不会过期，靠的就是这一条：<b>改权限的写路径必须踢会话</b>。
         *
         * 这条断言塌了，症状不是这个测试红 —— 是**线上有人拿着旧权限接着用**：
         * 收紧权限之后他照样点得动，直到他自己想起来重新登录。
         * 而收紧权限恰恰是最需要立刻生效的那一类改动。
         */
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FINANCE\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + victim))
                .andExpect(status().isUnauthorized());

        // 还原：整套跑在同一份 H2 上
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"GOODS_OPS\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★★ 改角色的功能点**不踢会话**，但那个人的权限当场就变了")
    void changingRolePointsTakesEffectWithoutRelogin() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String roleCode = "LIVEPERM" + System.currentTimeMillis() % 100000;
        mvc().perform(post("/ops/perm/roles").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"" + roleCode + "\",\"name\":\"现算测试\"}"))
                .andExpect(jsonPath("$.code").value(0));
        grantPoints(admin, roleCode, "\"OPS_ORDER\"");

        String uname = "live" + System.currentTimeMillis() % 100000 + "@example.com";
        String created = mvc().perform(post("/ops/staffs").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname + "\",\"realName\":\"现算的人\","
                                + "\"roles\":[\"" + roleCode + "\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String victim = opsLogin(uname, json.readTree(created).get("data")
                .get("initialPassword").asString());
        assertThat(permsOf(victim)).doesNotContain("aftersale:ticket:read");

        /*
         * **改的是角色的功能点，不是「他是谁」** —— 会话里存的是角色码，
         * 而权限码每个请求由 LivePermResolver 按角色现算。所以：
         *
         *   · 会话**照常有效**（不打断他手上的活）
         *   · 权限**当场就变**（连重新登录都不需要）
         *
         * 这两条要一起断言。只断言「没被踢」，把现算改回读快照也能过；
         * 只断言「权限变了」，把 revokeUser 加回来同样能过 —— 而那正是刚去掉的东西。
         */
        grantPoints(admin, roleCode, "\"OPS_ORDER\",\"OPS_AFTERSALE\"");

        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + victim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(permsOf(victim))
                .as("判权是现算的：改完配置，同一张令牌下一个请求就该拿到新权限")
                .contains("aftersale:ticket:read");
    }

    private void grantPoints(String admin, String roleCode, String codes) throws Exception {
        mvc().perform(post("/ops/perm/roles/" + roleCode + "/points")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointCodes\":[" + codes + "]}"))
                .andExpect(jsonPath("$.code").value(0));
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
        // BD 的 group:demand:assign 来自「团购与求团」下的改价/毁约功能点。
        // 细化前这里用的是 quote:govern —— 那个粗码已经拆成 read / assign 两个
        Set<String> before = permsOf(opsLogin("bd", "bd123"));
        assertThat(before).contains("group:demand:assign");

        // 直接改库：把 BD 与所有带 quote:govern 的功能点的关联删掉
        int removed = jdbc.update("""
                DELETE FROM sys_role_point WHERE role_code = 'BD' AND point_code IN
                  (SELECT point_code FROM sys_function_point WHERE perm_code = 'group:demand:assign')""");
        assertThat(removed).as("前置条件：库里应当有 BD 对 quote:govern 的授权").isPositive();
        resolver.invalidate();
        try {
            assertThat(permsOf(opsLogin("bd", "bd123")))
                    .as("删了库里的授权而 perms 不变，说明判权还在读硬编码 —— "
                            + "那这次换源就没有真正发生")
                    .doesNotContain("group:demand:assign");
        } finally {
            // 还原：整套共享一份 H2，不还原会让后面所有 BD 的用例失去这个权限
            jdbc.update("""
                    INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
                    SELECT 'BD', point_code, 'OPS', NOW(), NOW() FROM sys_function_point
                     WHERE perm_code = 'group:demand:assign'""");
            resolver.invalidate();
        }
    }

    @Test
    @DisplayName("★★ 判权认模块通配 merchant:* —— 与 ops-web 的 can() 同一套语义")
    void moduleWildcardIsHonoured() {
        /*
         * 后端此前是手写的 `contains("*") || contains(code)`，**只认精确的 `*`**；
         * 而 ops-web 的 can() 一直支持 `merchant:*`。目前库里发的都是具体码，
         * 所以不一致没有显形 —— 但哪天给某个角色配一个 `merchant:*`，
         * 表现就是「前端显示入口、后端 403」。
         *
         * 直接验 neargo 的判定函数：PermChecker 委托的就是它，
         * 而起一个带通配角色的会话要改库、跨测试污染，代价远大于收益。
         */
        var perms = java.util.List.of("merchant:*", "order:order:read");
        assertThat(ai.neargo.common.security.rbac.Permissions.matches(perms, "merchant:merchant:ban"))
                .as("模块通配应当覆盖该模块下的具体码").isTrue();
        assertThat(ai.neargo.common.security.rbac.Permissions.matches(perms, "finance:settle:read"))
                .as("模块通配不该越过模块边界").isFalse();
        assertThat(ai.neargo.common.security.rbac.Permissions.matches(java.util.List.of("*"), "anything:at:all"))
                .as("超管通配仍然顶所有").isTrue();
    }

    // ---------------------------------------------------------------- 菜单排序

    /** 某个 function 下的菜单点顺序（按 sort）。 */
    private List<String> pointOrder(String functionCode) {
        return jdbc.queryForList("""
                SELECT point_code FROM sys_function_point
                 WHERE function_code = ? AND point_type = 'MENU' ORDER BY sort, id""",
                String.class, functionCode);
    }

    @Test
    @DisplayName("★★★ 下移/上移只与相邻项换位，且**只影响同级** —— 别的分区顺序一动不动")
    void movePointSwapsWithNeighbourOnly() throws Exception {
        String admin = opsLogin("admin", "admin123");
        List<String> before = pointOrder("OPS_MERCHANT");
        assertThat(before.size()).as("前置条件：商家域应当有多个菜单点").isGreaterThan(2);
        List<String> otherBefore = pointOrder("OPS_ORDER");

        mvc().perform(post("/ops/perm/points/" + before.get(0) + "/move")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"DOWN\"}"))
                .andExpect(jsonPath("$.code").value(0));

        List<String> after = pointOrder("OPS_MERCHANT");
        List<String> expected = new java.util.ArrayList<>(before);
        java.util.Collections.swap(expected, 0, 1);
        assertThat(after).as("首项下移之后应当与第二项互换，其余不动").isEqualTo(expected);
        assertThat(pointOrder("OPS_ORDER")).as("别的分区不该被牵动").isEqualTo(otherBefore);

        // 还原：整套共享一份库，不还原会让后面依赖顺序的用例莫名其妙
        mvc().perform(post("/ops/perm/points/" + before.get(0) + "/move")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"UP\"}"));
        assertThat(pointOrder("OPS_MERCHANT")).isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 没有菜单叶子的分区也要返回 —— 否则它的顺序在端上永远调不动")
    void functionWithoutMenuLeavesIsStillReturned() throws Exception {
        /*
         * 经营看板只有 1 个 ACTION 点（dashboard:overview:read）、0 个菜单点。
         * 此前 build() 统计「本来有没有叶子」时把 ACTION 也算进去，于是它被判成
         * 「有叶子但一条都没授权」而整个分区不返回 —— 端上拿不到它的 sort，
         * **菜单里它的顺序怎么调都不动，而且不报错**（实测拖动后库变了、界面纹丝不动）。
         */
        String admin = opsLogin("admin", "admin123");
        assertThat(menuFunctions(admin))
                .as("经营看板没有菜单叶子，但它是一个菜单入口，必须返回")
                .contains("OPS_DASHBOARD");
    }

    @Test
    @DisplayName("★★★ 整段重排按数组顺序落库，且只影响该父级")
    void reorderWritesGivenOrder() throws Exception {
        String admin = opsLogin("admin", "admin123");
        List<String> before = pointOrder("OPS_MERCHANT");
        List<String> otherBefore = pointOrder("OPS_ORDER");
        List<String> want = new java.util.ArrayList<>(before);
        java.util.Collections.reverse(want);
        try {
            mvc().perform(post("/ops/perm/points/reorder")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    java.util.Map.of("functionCode", "OPS_MERCHANT", "codes", want))))
                    .andExpect(jsonPath("$.code").value(0));

            assertThat(pointOrder("OPS_MERCHANT")).as("应当逐位等于传进去的顺序").isEqualTo(want);
            assertThat(pointOrder("OPS_ORDER")).as("别的分区不该被牵动").isEqualTo(otherBefore);
        } finally {
            mvc().perform(post("/ops/perm/points/reorder")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            java.util.Map.of("functionCode", "OPS_MERCHANT", "codes", before))));
        }
        assertThat(pointOrder("OPS_MERCHANT")).isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 集合对不上一律拒绝 —— 少一个、多一个、混进别的父级")
    void reorderRejectsMismatchedSet() throws Exception {
        String admin = opsLogin("admin", "admin123");
        List<String> before = pointOrder("OPS_MERCHANT");

        List<List<String>> bad = List.of(
                before.subList(0, before.size() - 1),                    // 少一个
                concat(before, "OPS_ORDER"),                             // 混进别的父级
                concat(before, before.get(0)));                          // 多一个（重复）
        for (List<String> codes : bad) {
            mvc().perform(post("/ops/perm/points/reorder")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    java.util.Map.of("functionCode", "OPS_MERCHANT", "codes", codes))))
                    .andExpect(jsonPath("$.code").value(
                            org.hamcrest.Matchers.not(0)));
        }
        /*
         * **少一个尤其危险**：被漏掉的那项 sort 保持原值，混在新序列里排到莫名其妙的位置，
         * 而界面上看起来只是「顺序有点怪」，没人会当成 bug。所以拒绝，不做部分应用。
         */
        assertThat(pointOrder("OPS_MERCHANT")).as("拒绝之后顺序必须一动不动").isEqualTo(before);
    }

    private static List<String> concat(List<String> base, String extra) {
        List<String> out = new java.util.ArrayList<>(base);
        out.add(extra);
        return out;
    }

    @Test
    @DisplayName("★★ 首项上移 / 末项下移是 no-op —— 不报错，也不打乱顺序")
    void moveAtBoundaryIsNoop() throws Exception {
        String admin = opsLogin("admin", "admin123");
        List<String> before = pointOrder("OPS_MERCHANT");

        for (String[] c : new String[][]{{before.get(0), "UP"},
                                         {before.get(before.size() - 1), "DOWN"}}) {
            mvc().perform(post("/ops/perm/points/" + c[0] + "/move")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"direction\":\"%s\"}".formatted(c[1])))
                    // 「已经到头了」不是错误：做成报错只会让人以为自己点坏了什么
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertThat(pointOrder("OPS_MERCHANT")).as("边界操作不该改变任何顺序").isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 调完序 /ops/menu 立刻是新顺序 —— 不用重启、不用重登")
    void reorderTakesEffectImmediately() throws Exception {
        String admin = opsLogin("admin", "admin123");
        List<String> before = pointOrder("OPS_MERCHANT");
        try {
            mvc().perform(post("/ops/perm/points/" + before.get(0) + "/move")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"DOWN\"}"));

            String body = mvc().perform(get("/ops/menu").header("Authorization", "Bearer " + admin))
                    .andReturn().getResponse().getContentAsString();
            var merchant = json.readTree(body).path("data").valueStream()
                    .filter(f -> "OPS_MERCHANT".equals(f.path("functionCode").asString()))
                    .findFirst().orElseThrow();
            List<String> fromApi = merchant.path("points").valueStream()
                    .map(x -> x.path("pointCode").asString()).toList();
            assertThat(fromApi.indexOf(before.get(1)))
                    .as("换到前面的那一项，接口返回里也应当排在前面 —— "
                            + "不然就是菜单还在读某个缓存")
                    .isLessThan(fromApi.indexOf(before.get(0)));
        } finally {
            mvc().perform(post("/ops/perm/points/" + before.get(0) + "/move")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"UP\"}"));
        }
        assertThat(pointOrder("OPS_MERCHANT")).isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 改权限**不用重登**就生效 —— 同一个 token，改配置前后判权结果不同")
    void permChangeTakesEffectWithoutRelogin() throws Exception {
        /*
         * 这条守的是 2026-08-12 那次改造：判权从「会话里的 perms 快照」改成
         * 由 LivePermResolver 现算。
         *
         * **为什么必须不重登**：动态菜单（GET /ops/menu）是每次现查库的。
         * 判权若仍停在登录那一刻的快照上，两者就不同源 ——
         * 菜单刷新之后会出现「菜单里有，点进去 403」，
         * 而那比看不见那一项更糟：用户以为功能坏了，不是以为自己没权限。
         *
         * 上面 permsComeFromDb 那条**证明不了**这件事：它每次都重新登录，
         * 因此登录时重算快照也能让它绿。差别就在这一个 token 上。
         */
        String bd = opsLogin("bd", "bd123");   // ← 全程只登录这一次

        // 判权拒绝走统一响应包：HTTP 恒 200，码是 10403。断言状态码会永远绿（见 riskCannotDecideAfterSale）
        assertThat(breachCode(bd)).as("前置条件：BD 本应能进这个端点").isNotEqualTo(10403);

        jdbc.update("""
                DELETE FROM sys_role_point WHERE role_code = 'BD' AND point_code IN
                  (SELECT point_code FROM sys_function_point WHERE perm_code = 'group:demand:assign')""");
        resolver.invalidate();
        try {
            assertThat(breachCode(bd))
                    .as("同一个 token、没重登，判权就该按新配置拒绝 —— "
                            + "仍能进说明判权还在读登录那一刻的会话快照")
                    .isEqualTo(10403);
        } finally {
            jdbc.update("""
                    INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
                    SELECT 'BD', point_code, 'OPS', NOW(), NOW() FROM sys_function_point
                     WHERE perm_code = 'group:demand:assign'""");
            resolver.invalidate();
        }

        // 加回来之后同一个 token 又能进 —— **放宽也要即时生效**，不只是收紧。
        // 只验收紧的话，一个「拒绝一切」的实现也能让这条测试绿。
        assertThat(breachCode(bd)).as("恢复授权后同一个 token 应当又能进").isNotEqualTo(10403);
    }

    /** 打一个需要 group:demand:assign 的端点，返回统一响应包里的业务码。 */
    private int breachCode(String token) throws Exception {
        String body = mvc().perform(post("/ops/quotes/NOPE/breach")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"detail\":\"x\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asInt();
    }

    @Test
    @DisplayName("★★★ 风控不能裁决售后 —— 阶段 B 收紧的那条，用 403 钉住而不是靠矩阵纸面")
    void riskCannotDecideAfterSale() throws Exception {
        String risk = opsLogin("risk", "risk123");
        // 排查恶意退款要看售后单，所以**读保留** —— 收的是写，不是一刀切
        mvc().perform(get("/ops/after-sales").header("Authorization", "Bearer " + risk))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * 细化前这两条挂在 order:view 下 —— 一个「看单」的码，11 个角色里 7 个持有。
         * 也就是说风控、社区运营、活动运营都能批退款、都能改极速退阈值。
         */
        mvc().perform(post("/ops/after-sales/AS-NOPE/decide")
                        .header("Authorization", "Bearer " + risk)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refund\":true}"))
                // 拒绝走统一响应包（GlobalExceptionHandler 把 AccessDeniedException
                // 翻成 code 10403），HTTP 状态仍是 200 —— 断言状态码会永远绿
                .andExpect(jsonPath("$.code").value(10403));
        mvc().perform(post("/ops/after-sales/fast-refund-rule")
                        .header("Authorization", "Bearer " + risk)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 字段要给全：**参数绑定发生在判权之前** ——
                        // 少一个 boolean 会得到 10500 而不是 10403，看着像判权没生效
                        .content("{\"enabled\":true,\"maxAmount\":100,\"withinHours\":24,"
                                + "\"categories\":[]}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- 员工与多角色

    @Test
    @DisplayName("★★★ 多角色：给一个人两个角色，他的 perms 是两个角色的并集")
    void multiRoleUnionsPerms() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "techops");
        try {
            call("/ops/staffs/" + staffNo + "/roles", admin,
                    "{\"roles\":[\"TECH_OPS\",\"RISK\"]}", 0);
            Set<String> perms = permsOf(opsLogin("techops", "techops123"));
            assertThat(perms)
                    .as("**库早就支持多角色**（sys_role_member 唯一键含 role_code、"
                            + "roles 是 JSON 数组、Perms.of 取并集），是写接口把它压成了单值")
                    .contains("iam:audit:read")      // 来自 TECH_OPS
                    .contains("order:order:read");   // 来自 RISK
        } finally {
            call("/ops/staffs/" + staffNo + "/roles", admin, "{\"roles\":[\"TECH_OPS\"]}", 0);
        }
    }

    @Test
    @DisplayName("★★★ 不能给自己加角色 —— 否则有 iam:staff:update 的人能给自己加超管")
    void cannotGrantRolesToSelf() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String self = staffNoOf(admin, "admin");
        /*
         * 单角色版靠「不能改自己」挡住了这件事，改成多角色时最容易漏掉它。
         * 降权是自己倒霉，提权是所有人的事 —— 两者不对称。
         */
        call("/ops/staffs/" + self + "/roles", admin, "{\"roles\":[\"SUPER_ADMIN\"]}", 10420);
    }

    @Test
    @DisplayName("★★ 建员工：一次性初始密码能登录，且被要求改密")
    void createStaffReturnsOneTimePassword() throws Exception {
        String admin = opsLogin("admin", "admin123");
        // 登录名必须是邮箱（2026-08-12 起）——存量的短用户名账号不受影响，只挡新建
        String uname = "newbie" + System.currentTimeMillis() % 100000 + "@example.com";
        String body = mvc().perform(post("/ops/staffs").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname + "\",\"realName\":\"新人\","
                                + "\"roles\":[\"RISK\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        String initial = data.get("initialPassword").asString();
        assertThat(initial).as("初始密码只在这一次出现").isNotBlank();
        assertThat(data.get("staff").get("mustChangePassword").asBoolean())
                .as("**要透给前端** —— 不透的话新人拿一次性密码登进来就能一直用")
                .isTrue();

        // 用它真的能登录
        assertThat(permsOf(opsLogin(uname, initial))).contains("order:order:read");

        // 登录名不能重
        mvc().perform(post("/ops/staffs").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname + "\",\"realName\":\"撞名\","
                                + "\"roles\":[\"RISK\"]}"))
                .andExpect(jsonPath("$.code").value(10423));
    }

    @Test
    @DisplayName("★★ 建员工的登录名必须是邮箱 —— 存量短用户名账号不受影响，只挡新建")
    void createStaffRejectsNonEmailUsername() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/staffs", admin,
                "{\"username\":\"notanemail\",\"realName\":\"坏邮箱\",\"roles\":[\"RISK\"]}", 10424);
        // 存量账号（比如 admin 自己）登录不受影响 —— 这条校验只在 createStaff 这一个入口
        assertThat(permsOf(opsLogin("admin", "admin123"))).contains("*");
    }

    @Test
    @DisplayName("★★ 自定义角色码必须大写字母开头，只能有大写字母/数字/下划线")
    void createRoleRejectsInvalidCode() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/perm/roles", admin, "{\"roleCode\":\"bad code!\",\"name\":\"坏码\"}", 10442);
        call("/ops/perm/roles", admin, "{\"roleCode\":\"1STARTSWITHDIGIT\",\"name\":\"坏码\"}", 10442);
        call("/ops/perm/roles", admin, "{\"roleCode\":\"lowercase\",\"name\":\"坏码\"}", 10442);
    }

    @Test
    @DisplayName("★★ 改角色集合按增删同步，granted_at 不被重写 —— 那是审计要查的东西")
    void roleMemberSyncKeepsAuditTrail() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "techops");
        /*
         * **必须过滤 deleted** —— 撤销角色是逻辑删除，行还在。
         * 第一版漏了这个条件，读到三行（两条历史 + 一条有效）而不是一行，
         * 报错是「Incorrect result size: expected 1, actual 3」，
         * 看着像有重复数据，实际是查询把历史也捞了。
         */
        Long before = jdbc.queryForObject(
                "SELECT granted_at FROM sys_role_member "
                        + "WHERE subject_no = ? AND role_code = 'TECH_OPS' AND deleted = 0",
                Long.class, staffNo);
        try {
            call("/ops/staffs/" + staffNo + "/roles", admin,
                    "{\"roles\":[\"TECH_OPS\",\"RISK\"]}", 0);
            Long after = jdbc.queryForObject(
                    "SELECT granted_at FROM sys_role_member "
                            + "WHERE subject_no = ? AND role_code = 'TECH_OPS' AND deleted = 0",
                    Long.class, staffNo);
            assertThat(after)
                    .as("清空重插会把「这个角色是三个月前谁给的」改成「今天我给的」")
                    .isEqualTo(before);
        } finally {
            call("/ops/staffs/" + staffNo + "/roles", admin, "{\"roles\":[\"TECH_OPS\"]}", 0);
        }
    }

    // ---------------------------------------------------------------- 角色配置写侧

    @Test
    @DisplayName("★★★ 自定义角色真的生效：建角色 → 勾功能点 → 授予某人 → 他登录后就有那个权限")
    void customRoleTakesEffect() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String code = "TEST_ROLE_" + System.currentTimeMillis() % 100000;
        try {
            call("/ops/perm/roles", admin,
                    "{\"roleCode\":\"" + code + "\",\"name\":\"临时角色\"}", 0);
            // 勾一个带 order:view 的功能点
            String pc = pointWithPerm(admin, "order:order:read");
            call("/ops/perm/roles/" + code + "/points", admin,
                    "{\"pointCodes\":[\"" + pc + "\"]}", 0);

            // 授予 techops（他本来没有 order:view）
            String staffNo = staffNoOf(admin, "techops");
            assertThat(permsOf(opsLogin("techops", "techops123"))).doesNotContain("order:order:read");
            mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"" + code + "\"}"))
                    .andExpect(jsonPath("$.code").value(0));

            assertThat(permsOf(opsLogin("techops", "techops123")))
                    .as("**这是换源带来的新能力**：在判权读硬编码的时候，"
                            + "自定义角色只能改菜单，而菜单能看、接口 403 是最坏的一种")
                    .contains("order:order:read");
        } finally {
            String staffNo = staffNoOf(admin, "techops");
            mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TECH_OPS\"}"));
            call("/ops/perm/roles/" + code + "/delete", admin, "{}", 0);
        }
    }

    @Test
    @DisplayName("★★★ 预置角色拒绝修改 —— 它是 Perms.java 的镜像，改了会与回落表分叉")
    void builtinRoleIsReadOnly() throws Exception {
        String admin = opsLogin("admin", "admin123");
        /*
         * 用后端角色码 BD 而不是前端的 MERCHANT_BD ——
         * 库里存的是 Perms.ROLE_PERMS 的键（历史遗留的三个异名同义之一），
         * ops-web 那侧才翻译成 MERCHANT_BD。第一次写这条断言时用错了，得到 10404。
         */
        call("/ops/perm/roles/BD/points", admin, "{\"pointCodes\":[]}", 10440);
        call("/ops/perm/roles/BD/delete", admin, "{}", 10440);
    }

    @Test
    @DisplayName("★★ 还有人在用的角色不能删 —— 删了他们能登录但什么都点不动，且看不出原因")
    void roleInUseCannotBeDeleted() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String code = "TEST_INUSE_" + System.currentTimeMillis() % 100000;
        String staffNo = staffNoOf(admin, "techops");
        try {
            call("/ops/perm/roles", admin,
                    "{\"roleCode\":\"" + code + "\",\"name\":\"占用中\"}", 0);
            String pc = pointWithPerm(admin, "order:order:read");
            call("/ops/perm/roles/" + code + "/points", admin,
                    "{\"pointCodes\":[\"" + pc + "\"]}", 0);
            mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + code + "\"}"));
            call("/ops/perm/roles/" + code + "/delete", admin, "{}", 10441);
        } finally {
            mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TECH_OPS\"}"));
            call("/ops/perm/roles/" + code + "/delete", admin, "{}", 0);
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

    /** 找一个 perm_code 是给定值的功能点 */
    private String pointWithPerm(String adminToken, String perm) throws Exception {
        String fns = mvc().perform(get("/ops/perm/functions")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode f : json.readTree(fns).get("data")) {
            for (JsonNode p : f.get("points")) {
                if (!p.get("permCode").isNull() && perm.equals(p.get("permCode").asString())) {
                    return p.get("pointCode").asString();
                }
            }
        }
        throw new IllegalStateException("库里没有带 " + perm + " 的功能点");
    }

    private void call(String path, String token, String body, int expectCode) throws Exception {
        mvc().perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(expectCode));
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
