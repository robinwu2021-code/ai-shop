package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 商家自定义角色（V71）。
 *
 * <p><b>这个文件里最重要的是第一条</b>：自定义角色不得包含 {@code biz:store:admin}。
 * 放开自定义角色时，那是唯一一条不能一起放开的东西 ——
 * 它是「管人」的码，授出去等于让被授权的人能改所有人的授权、给自己加任何角色，
 * <b>一次授权就绕开了整个模型</b>。
 *
 * <p>界面上它根本不出现，但界面那道是体验；端点是公开的，
 * <b>绕过界面直接发一个带它的请求是最容易想到的事</b>。所以边界必须在后端，
 * 而后端的边界必须有测试 —— 它一旦失效就是提权，且没有任何症状。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantRoleFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 边界

    @Test
    @DisplayName("★★★ 自定义角色不得包含 biz:store:admin —— 放开自定义时唯一不能放开的那条")
    void cannotGrantStoreAdminViaCustomRole() throws Exception {
        String owner = merchant("12700270001", "提权测试店");

        mvc().perform(post("/biz/roles").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"副老板\",\"perms\":[\"biz:order:view\",\"biz:store:admin\"]}"))
                .andExpect(jsonPath("$.code").value(70006));

        // 单独一个也不行 —— 不是「混在里面才拦」
        mvc().perform(post("/biz/roles").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"管人的\",\"perms\":[\"biz:store:admin\"]}"))
                .andExpect(jsonPath("$.code").value(70006));

        // 通配同理：它只属于 OWNER，而 OWNER 不走这张表
        mvc().perform(post("/biz/roles").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"全能\",\"perms\":[\"*\"]}"))
                .andExpect(jsonPath("$.code").value(70006));
    }

    @Test
    @DisplayName("★★ 预置角色改不动、删不掉 —— 要改先复制一份")
    void builtinRolesAreReadOnly() throws Exception {
        String owner = merchant("12700270002", "预置只读店");

        mvc().perform(post("/biz/role/MANAGER").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改过的店长\",\"perms\":[\"biz:order:view\"]}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/biz/role/CLERK/delete").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★★ 还有人在用的角色删不掉 —— 删了那些人的权限凭空消失且没有解释")
    void cannotDeleteRoleInUse() throws Exception {
        String owner = merchant("12700270003", "占用角色店");
        String store = firstStore(owner);
        String roleCode = createRole(owner, "夜班店长", "\"biz:order:view\",\"biz:ship\"");

        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12700270013\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + store + "\",\"role\":\"" + roleCode + "\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/biz/role/" + roleCode + "/delete")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(10409));
    }

    // ---------------------------------------------------------------- 判权链路

    @Test
    @DisplayName("★★★ 自定义角色真的能判权，且**改完下一个请求就生效**")
    void customRoleTakesEffectImmediately() throws Exception {
        String owner = merchant("12700270004", "自定义生效店");
        String store = firstStore(owner);
        // 先只给「看订单」
        String roleCode = createRole(owner, "夜班", "\"biz:order:view\"");

        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12700270014\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"" + store + "\",\"role\":\"" + roleCode + "\"}"));

        String staff = staffLogin("12700270014");
        // 有 order:view → 通
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + staff))
                .andExpect(jsonPath("$.code").value(0));
        // 没有 stock → 70006
        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + staff))
                .andExpect(jsonPath("$.code").value(70006));

        /*
         * **老板加一个权限码，员工不用重新登录**。
         *
         * 这正是判权链路选「解析身份时算 permsByStore」而不是「写进 token」的理由：
         * 收回权限必须立刻生效，而写进 token 的话要等他下次登录 ——
         * 那中间的窗口里，一个已经被收回权限的人还在用着旧 token。
         */
        mvc().perform(post("/biz/role/" + roleCode).header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"夜班\",\"perms\":[\"biz:order:view\",\"biz:stock\"]}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + staff))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★ 角色定义的变更也要留痕 —— 它比给某个人授权影响更大")
    void roleChangesAreAudited() throws Exception {
        String owner = merchant("12700270005", "角色留痕店");
        String roleCode = createRole(owner, "对账员", "\"biz:finance\"");
        mvc().perform(post("/biz/role/" + roleCode).header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"对账员\",\"perms\":[\"biz:finance\",\"biz:order:view\"]}"));

        String body = mvc().perform(get("/biz/staff/logs").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("ROLE_CREATE").contains("ROLE_UPDATE");
        // 改成了什么要写在 detail 里 —— 事后问「他怎么突然能看订单了」，答案在这一行
        assertThat(body).contains("看订单与金额");
    }

    @Test
    @DisplayName("★ 角色列表带中文说明与「几个人在用」—— 勾权限码时不能让老板盲选")
    void listCarriesLabelsAndUsage() throws Exception {
        String owner = merchant("12700270006", "角色列表店");
        String body = mvc().perform(get("/biz/roles").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("预置六角色要在列表里").contains("MANAGER").contains("店长");
        assertThat(body).as("权限码要带中文，`biz:stock` 对店主没有意义").contains("改库存");
        assertThat(body).contains("usedBy");
    }

    @Test
    @DisplayName("★★ 可勾权限点由后端给全 —— 端上拿预置角色并集会漏掉 biz:finance")
    void assignablePermsComeFromBackend() throws Exception {
        String owner = merchant("12700270007", "可勾清单店");
        String body = mvc().perform(get("/biz/role-perms").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        /*
         * 端上原先把 6 个预置角色的权限并起来当勾选项，**少一条**：
         * biz:finance 只有 OWNER 有，而 OWNER 那行是 `*`。
         * 后端收这个码，界面却勾不到 —— 看起来像功能没做。
         */
        assertThat(body).as("这条正是并集漏掉的那条").contains("biz:finance");
        assertThat(body).as("要带中文，界面上不出现 biz:xxx").contains("结算账单与收款进件");
        assertThat(body).as("管人的码不下发 —— 与建角色时的拒绝同一份定义")
                .doesNotContain("biz:store:admin");
        // 少一条就是「有个功能授不出去」，多一条就是提权：数量本身要钉住
        assertThat(json.readTree(body).get("data").size()).isEqualTo(12);
    }

    @Test
    @DisplayName("★ 审计里写角色的中文名 —— 自定义角色的码是 R-… 一串，写进日志没人读得懂")
    void auditSpellsRoleName() throws Exception {
        String owner = merchant("12700270008", "审计中文店");
        String roleCode = createRole(owner, "夜班店长", "\"biz:order:view\"");
        String phone = "12700270081";
        mvc().perform(post("/biz/staff").header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginPhone\":\"" + phone + "\",\"displayName\":\"小夜\"}"));
        String accountNo = json.readTree(mvc().perform(get("/biz/staff")
                        .header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(1).get("mchAccountNo").asString();

        mvc().perform(post("/biz/staff/" + accountNo + "/store")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + firstStore(owner) + "\",\"role\":\""
                                + roleCode + "\",\"granted\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String logs = mvc().perform(get("/biz/staff/logs").header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsString();
        assertThat(logs).as("detail 里要写「夜班店长」").contains("的 夜班店长");
        assertThat(logs).as("detail 里不该出现生成的角色码")
                .doesNotContain("的 " + roleCode);
    }

    @Test
    @DisplayName("★★★ /biz/context 要下发自定义角色的权限 —— 否则后端放行、界面什么都不显示")
    void contextCarriesCustomRolePerms() throws Exception {
        String owner = merchant("12700270009", "作用域下发店");
        String store = firstStore(owner);
        // 预置角色里**没有**这个组合：营销 + 看订单
        String roleCode = createRole(owner, "夜班店员", "\"biz:campaign\",\"biz:order:view\"");

        String phone = "12700270091";
        mvc().perform(post("/biz/staff").header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginPhone\":\"" + phone + "\"}"));
        String accountNo = json.readTree(mvc().perform(get("/biz/staff")
                        .header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(1).get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + accountNo + "/store")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"" + store + "\",\"role\":\"" + roleCode + "\"}"));

        String staff = staffLogin(phone);
        String scope = mvc().perform(get("/biz/context").header("Authorization", "Bearer " + staff))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        /*
         * 判权用 permsByStore，而这个端点曾经自己按 BizPerms.of(staffRoles) 又算一遍 ——
         * 那张表只认预置角色，于是自定义角色的权限**一个都不下发**。
         * 表现不是报错，是「这个功能看起来还没做」：后端放行，界面不画入口。
         */
        assertThat(scope).as("端上照它裁剪入口").contains("biz:campaign").contains("biz:order:view");
        // 反面：没授的码不能凭空出现
        assertThat(scope).doesNotContain("biz:store:admin");
        // 而且它确实能调 —— 判权与下发是同一个来源
        mvc().perform(get("/biz/campaign").header("Authorization", "Bearer " + staff))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------------------------------------------------------- 装配

    private String createRole(String owner, String name, String permsJson) throws Exception {
        String body = mvc().perform(post("/biz/roles").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"perms\":[" + permsJson + "]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("roleCode").asString();
    }

    private String firstStore(String ownerToken) throws Exception {
        return json.readTree(mvc().perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("storeNo").asString();
    }

    private String staffLogin(String phone) throws Exception {
        mvc().perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/biz/auth/staff-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
