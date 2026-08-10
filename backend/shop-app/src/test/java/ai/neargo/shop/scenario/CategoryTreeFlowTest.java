package ai.neargo.shop.scenario;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 类目树（{@code docs/technical/类目树补齐方案.md}）。
 *
 * <p>这块此前是「表建了、tree() 写了、ops-web 页面也做了，唯独没有数据也没有后端 CRUD」——
 * 于是运营端类目页在真实环境是四个 404，而 mock 上一切正常。
 * 这组用例守的就是那种「三处各自自洽、合起来不通」的缺口。
 */
@SpringBootTest
@ActiveProfiles("test")
class CategoryTreeFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 树本身

    @Test
    @DisplayName("★ 类目树有数据 —— 空表时 tree() 返回 []，分类选择器是一片空白且不报错")
    void treeIsSeeded() throws Exception {
        String body = mvc().perform(get("/mp/category/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode tree = json.readTree(body).get("data");
        assertThat(tree).isNotEmpty();

        // 编号必须与 V4 迁移、ops-web mock、shared mock 一致 —— 四处对不上时，
        // 症状是「mock 上跑得通、连真库就找不到类目」
        JsonNode fresh = find(tree, "CAT100");
        assertThat(fresh).isNotNull();
        assertThat(fresh.get("name").asString()).isEqualTo("食品生鲜");

        // 三级确实挂到了三级，而不是全平铺在一级
        JsonNode veg = find(fresh.get("children"), "CAT110");
        assertThat(veg).isNotNull();
        assertThat(find(veg.get("children"), "CAT111")).isNotNull();
        assertThat(find(veg.get("children"), "CAT111").get("level").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("B 端有自己的类目入口 —— 端上有前缀守卫，B 端不能去调 /mp/**")
    void bizHasItsOwnTreeEndpoint() throws Exception {
        // 未登录先确认这条路由存在（存在 → 401；不存在 → 404）
        mvc().perform(get("/biz/category/tree")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 平台端维护

    @Test
    @DisplayName("运营能列出类目，且带着 skuCount 与资质门槛")
    void opsCanListCategories() throws Exception {
        String token = opsLogin();
        String body = mvc().perform(get("/ops/categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = json.readTree(body).get("data");
        assertThat(rows).isNotEmpty();

        JsonNode leafy = findFlat(rows, "CAT111");
        assertThat(leafy).isNotNull();
        // 挂了资质门槛的类目，requiredCode 必须下发 —— 它是**校验依据**，不是展示文案
        assertThat(leafy.get("requiredCode").asString()).isEqualTo("FRESH_VEG");
        assertThat(leafy.get("template").asString()).isEqualTo("FRESH");
    }

    @Test
    @DisplayName("★ 三级封顶：在三级类目下再建子类目被拒")
    void cannotGoDeeperThanThreeLevels() throws Exception {
        String token = opsLogin();
        String body = mvc().perform(post("/ops/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第四级\",\"parentNo\":\"CAT111\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(80001);
    }

    @Test
    @DisplayName("★ level 由 parentNo 推出，不采信端上传的值")
    void levelIsDerivedNotTrusted() throws Exception {
        String token = opsLogin();
        // 端上「声称」这是一级类目，但它挂在 CAT100 下，实际必须是二级
        String body = mvc().perform(post("/ops/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"冻品\",\"parentNo\":\"CAT100\",\"level\":1,\"template\":\"FRESH\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("data").get("level").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 有子类目时不能归档 —— 否则子类目会挂在一个不存在的父节点下")
    void cannotArchiveWithChildren() throws Exception {
        String token = opsLogin();
        String body = mvc().perform(post("/ops/categories/CAT100/archive")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(80002);
    }

    @Test
    @DisplayName("归档后默认不出现在列表里，showArchived=true 才看得到；恢复后回到列表")
    void archiveThenUnarchive() throws Exception {
        String token = opsLogin();
        String created = mvc().perform(post("/ops/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"临时类目\",\"template\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String no = json.readTree(created).get("data").get("categoryNo").asString();

        mvc().perform(post("/ops/categories/" + no + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(findFlat(listCategories(token, false), no)).isNull();
        assertThat(findFlat(listCategories(token, true), no)).isNotNull();

        mvc().perform(post("/ops/categories/" + no + "/unarchive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(findFlat(listCategories(token, false), no)).isNotNull();
    }

    @Test
    @DisplayName("★ 归档的类目不出现在 C 端树里 —— 否则用户点进去是空列表")
    void archivedCategoryLeavesTheTree() throws Exception {
        String token = opsLogin();
        String created = mvc().perform(post("/ops/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"待归档一级\",\"template\":\"STANDARD\"}"))
                .andReturn().getResponse().getContentAsString();
        String no = json.readTree(created).get("data").get("categoryNo").asString();

        assertThat(find(tree(), no)).isNotNull();
        mvc().perform(post("/ops/categories/" + no + "/archive")
                .header("Authorization", "Bearer " + token));
        assertThat(find(tree(), no)).isNull();
    }

    @Test
    @DisplayName("客服没有 category:manage，改不了类目（改的是一整类商品的准入门槛）")
    void supportCannotManageCategories() throws Exception {
        String support = opsLogin("support", "support123");
        // @PreAuthorize 抛 AccessDeniedException，由 GlobalExceptionHandler 转成契约包
        // （HTTP 200 + code 10403）—— 401 是唯一发真状态码的分支
        mvc().perform(get("/ops/categories").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- 经营准入

    @Test
    @DisplayName("★ 无资质授权时，上架挂了门槛的类目被拒 —— 而保存草稿不拦")
    void listingGatedCategoryNeedsAuthorization() throws Exception {
        String token = merchant("12600131001", "准入测试·菜摊");

        // 保存到 CAT111（叶菜，要 FRESH_VEG）—— **这一步必须成功**：
        // 商家可能正准备去申请那张证，保存就拦住等于逼他归到错误的类目下
        String goodsNo = saveGoods(token, "青菜一把", "CAT111");

        approveGoods(goodsNo);

        // 上架才校验
        String body = mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(70002);
    }

    @Test
    @DisplayName("★ 运营授权之后，同一件商品就能上架了（门槛要有发证的一侧）")
    void authorizationOpensTheGate() throws Exception {
        String token = merchant("12600131002", "准入测试·授权后");
        String merchantNo = merchantNoOf(token);
        String goodsNo = saveGoods(token, "菠菜一把", "CAT111");
        approveGoods(goodsNo);

        String bd = opsLogin("bd", "bd123");
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/ops/merchants/" + merchantNo + "/auth-codes")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"FRESH_VEG\"],\"reason\":\"已核验食品经营许可证\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("无门槛类目照常上架 —— 准入不该把所有人都挡住")
    void ungatedCategoryStillWorks() throws Exception {
        String token = merchant("12600131003", "准入测试·纸品");
        String goodsNo = saveGoods(token, "抽纸一提", "CAT210");
        approveGoods(goodsNo);

        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("授权不能撤空 —— 撤空后商家静默失去上架能力，要停请走封禁")
    void cannotClearAllAuthCodes() throws Exception {
        String token = merchant("12600131004", "准入测试·撤空");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/ops/merchants/" + merchantNo + "/auth-codes")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[],\"reason\":\"停一停\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isNotEqualTo(0);
    }

    @Test
    @DisplayName("改授权必须写原因 —— 它决定商家能上架什么")
    void authChangeNeedsReason() throws Exception {
        String token = merchant("12600131005", "准入测试·无理由");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/ops/merchants/" + merchantNo + "/auth-codes")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"FRESH_VEG\"],\"reason\":\"  \"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isNotEqualTo(0);
    }

    @Test
    @DisplayName("授权码列表下发给运营 —— 没有它，授权页只能让人手打编码")
    void authCodesAreListed() throws Exception {
        String bd = opsLogin("bd", "bd123");
        String body = mvc().perform(get("/ops/merchants/auth-codes")
                        .header("Authorization", "Bearer " + bd))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode codes = json.readTree(body).get("data");
        assertThat(codes).isNotEmpty();
        boolean hasFreshVeg = false;
        for (JsonNode c : codes) {
            if ("FRESH_VEG".equals(c.get("code").asString())) {
                hasFreshVeg = true;
                assertThat(c.get("requiredQualification").asString()).isEqualTo("食品经营许可证");
            }
        }
        assertThat(hasFreshVeg).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode tree() throws Exception {
        String body = mvc().perform(get("/mp/category/tree"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode listCategories(String token, boolean showArchived) throws Exception {
        String body = mvc().perform(get("/ops/categories")
                        .param("showArchived", String.valueOf(showArchived))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    /** 树里递归找 */
    private JsonNode find(JsonNode nodes, String categoryNo) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode n : nodes) {
            if (categoryNo.equals(n.get("categoryNo").asString())) {
                return n;
            }
            JsonNode hit = find(n.get("children"), categoryNo);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 平铺列表里找 */
    private JsonNode findFlat(JsonNode rows, String categoryNo) {
        for (JsonNode n : rows) {
            if (categoryNo.equals(n.get("categoryNo").asString())) {
                return n;
            }
        }
        return null;
    }

    /** 建一件商品并落到指定类目，返回 goodsNo */
    private String saveGoods(String token, String title, String categoryNo) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subtitle\":\"测试\",\"type\":\"FRESH\","
                                + "\"categoryNo\":\"" + categoryNo + "\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    /** 过审 —— 上架要求已过审，否则会先撞上状态校验而不是准入校验 */
    private void approveGoods(String goodsNo) throws Exception {
        String goodsOps = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + goodsOps)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    /** 注册消费者 → 提交入驻 → BD 审核通过 → 重新登录拿到带商家身份的 token */
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

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // 商家身份是登录时解析进 BizContext 的，旧 token 上还没有
        return login(phone);
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String opsLogin() throws Exception {
        return opsLogin("goods", "goods123");
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
