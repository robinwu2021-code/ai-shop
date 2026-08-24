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

    /*
     * **闸门在这里显式打开**，拨的是真开关（平台 feature flag `category.gate.enforce`）。
     *
     * <p>生产默认关（只展示、不限制），而本类里那几条准入用例测的正是「拦得对不对」——
     * 跟着默认值走的话它们会在闸门关着时静静地"通过"，什么都没验到。
     *
     * <p>从前这里改的是 MerchantGoodsServiceImpl 的私有字段 gateEnforced，
     * 开关搬进 sys_setting 之后那个字段不存在了 —— **反射改字段的写法本来就脆**：
     * 它绕过了真实读取路径，字段一改名测试就崩，而崩的信息（找不到字段）
     * 与被测的业务毫无关系。现在拨的就是业务读的那一个。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.platform.PlatformConfigService platformConfig;

    @org.junit.jupiter.api.BeforeEach
    void openGate() {
        setGate(true);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreGate() {
        setGate(false);   // 复位成生产默认
    }

    private void setGate(boolean on) {
        platformConfig.saveFeatureFlag("category.gate.enforce", on, 0, "TEST");
    }


    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


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

        /*
         * 二级确实挂到了二级，而不是全平铺在一级。
         *
         * **原先这里断言的是三级（CAT111 叶菜）** —— V168 把类目降到两级：
         * 三级节点归档、资质码上移到二级。降级的理由是「商家选自己卖哪几类」
         * 这一步在三级树下没法存在，而叶菜/根茎菜的粒度对社区电商没有用。
         */
        JsonNode veg = find(fresh.get("children"), "CAT110");
        assertThat(veg).isNotNull();
        assertThat(veg.get("level").asInt()).isEqualTo(2);
        // 三级已经全部归档，树里不该再出现它们
        assertThat(find(veg.get("children"), "CAT111")).isNull();
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

        JsonNode rows = json.readTree(body).get("data").get("records");
        assertThat(rows).isNotEmpty();

        /*
         * 资质门槛现在挂在**二级**（V168 从三级上移）。判据没变，
         * 只是范围从「叶菜」扩到「蔬菜」—— 两者要的本来就是同一张食品经营许可证。
         */
        JsonNode veg = findFlat(rows, "CAT110");
        assertThat(veg).isNotNull();
        // 挂了资质门槛的类目，requiredCode 必须下发 —— 它是**校验依据**，不是展示文案
        assertThat(veg.get("requiredCode").asString()).isEqualTo("FRESH_VEG");
        assertThat(veg.get("template").asString()).isEqualTo("FRESH");
    }

    @Test
    @DisplayName("★ 两级封顶：在二级类目下再建子类目被拒")
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

        // 保存到 CAT110（蔬菜，要 FRESH_VEG —— V168 降二级后门槛上移到这里）—— **这一步必须成功**：
        // 商家可能正准备去申请那张证，保存就拦住等于逼他归到错误的类目下
        String goodsNo = saveGoods(token, "青菜一把", "CAT110");

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
        String goodsNo = saveGoods(token, "菠菜一把", "CAT110");
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
    @DisplayName("★★ 审核通过时一并授码 —— 不再有「通过了但一个码都没授」这个中间态")
    void auditGrantsCodesInOneStep() throws Exception {
        String phone = "12600131010";
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"授码测试·菜摊\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"grantCodes\":[\"FRESH_VEG\"]}"))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * ★ 判据不是「接口返回 0」，是**商家当场就能上架带门槛的货**。
         * 分两步时这里会是 70002：通过通知已经发出，而他一件生鲜都上不了架。
         */
        String token = TestLogin.consumer(mvc(), json, otpStore, phone);
        String goodsNo = saveGoods(token, "小油菜", "CAT110");
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 撤码时回一个「影响几件在架商品」—— 运营按确认之前要看得见代价")
    void revokingCodesReportsImpact() throws Exception {
        String token = merchant("12600131011", "撤码测试·菜摊");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        grantCodes(bd, merchantNo, "[\"FRESH_VEG\",\"DAILY\"]", "已核验");
        String goodsNo = saveGoods(token, "上海青", "CAT110");
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String resp = grantCodes(bd, merchantNo, "[\"DAILY\"]", "许可证已过期");
        JsonNode data = json.readTree(resp).get("data");
        assertThat(data.get("revoked").get(0).asString()).isEqualTo("FRESH_VEG");
        assertThat(data.get("affected").asInt()).as("那件在架的生鲜要被数出来").isEqualTo(1);
    }

    private String grantCodes(String opsToken, String merchantNo, String codesJson, String reason)
            throws Exception {
        return mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/ops/merchants/" + merchantNo + "/auth-codes")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":" + codesJson + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
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
                // 一期果蔬走**初级农产品**口径（V22）：执照就够，不需要食品经营许可证。
                // 留着旧文案的代价是运营授权时去要一张不需要的证
                assertThat(c.get("requiredQualification").asString()).isEqualTo("营业执照（食用农产品）");
            }
        }
        assertThat(hasFreshVeg).isTrue();
    }

    // ---------------------------------------------------------------- 商家治理（P-11.1）

    @Test
    @DisplayName("★ 违规处置的两个副作用是处置的一部分：BREACH 累加毁约、SUSPEND 真的封店")
    void violationHasRealConsequences() throws Exception {
        String token = merchant("12600151001", "治理测试·违规");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchants/" + merchantNo + "/violations")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BREACH\",\"action\":\"SUSPEND\","
                                + "\"detail\":\"成团后跑单，订单 SO123，聊天记录已存档\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String body = mvc().perform(get("/ops/merchants/" + merchantNo)
                        .header("Authorization", "Bearer " + bd))
                .andReturn().getResponse().getContentAsString();
        JsonNode m = json.readTree(body).get("data");
        // 只记录不执行的处置等于没处置 —— 商家那边什么都不会发生
        assertThat(m.get("breachCount").asInt()).isEqualTo(1);
        assertThat(m.get("status").asString()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("★ 处置必须写事实 —— 没有事实的处置在申诉时站不住")
    void violationNeedsFacts() throws Exception {
        String token = merchant("12600151002", "治理测试·无事实");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchants/" + merchantNo + "/violations")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SERVICE\",\"action\":\"WARN\",\"detail\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★ 认证标不给停业中的商家 —— 平台背书挂在封禁的店上，赔的是平台信用")
    void verifiedBadgeNeedsGoodStanding() throws Exception {
        String token = merchant("12600151003", "治理测试·认证标");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        // 正常经营时可以授标
        mvc().perform(post("/ops/merchants/" + merchantNo + "/verified")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":true}"))
                .andExpect(jsonPath("$.data.verified").value(true));

        mvc().perform(post("/ops/merchants/" + merchantNo + "/status")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"remark\":\"售假处罚\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/ops/merchants/" + merchantNo + "/verified")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":true}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("★ 封禁必须写说明 —— 不写的话商家只看到「店没了」")
    void suspendNeedsRemark() throws Exception {
        String token = merchant("12600151004", "治理测试·封禁说明");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchants/" + merchantNo + "/status")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"remark\":\"\"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("经营状态机：封禁中不能直接冻结（两者是不同性质的处置）")
    void operatingStateMachine() throws Exception {
        String token = merchant("12600151005", "治理测试·状态机");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchants/" + merchantNo + "/status")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\",\"remark\":\"售假\"}"));
        mvc().perform(post("/ops/merchants/" + merchantNo + "/status")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\",\"remark\":\"再冻一下\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("商家档案下发的是脱敏手机号 —— 完整号码属于越权边界")
    void profilePhoneIsMasked() throws Exception {
        String token = merchant("12600151006", "治理测试·脱敏");
        String merchantNo = merchantNoOf(token);
        String bd = opsLogin("bd", "bd123");

        String body = mvc().perform(get("/ops/merchants/" + merchantNo)
                        .header("Authorization", "Bearer " + bd))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("13900000000").contains("139****0000");
    }

    // ---------------------------------------------------------------- 门面内容审核（P-10.1）

    @Test
    @DisplayName("★ 干净的公告立刻生效 —— 不进审核队列（时效内容等不起人审）")
    void cleanNoticeTakesEffectImmediately() throws Exception {
        String token = merchant("12600161001", "门面测试·干净公告");

        mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"今日到货：本地小番茄\",\"openHours\":\"08:00-20:00\","
                                + "\"address\":\"文一西路 1 号\",\"featured\":[],"
                                + "\"serviceScope\":\"COMMUNITY\",\"serviceCommunityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/store").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.announcement").value("今日到货：本地小番茄"));
    }

    @Test
    @DisplayName("★ 命中敏感词转人审：内容不生效，但**保留旧公告**（清空会让店主以为改坏了）")
    void flaggedNoticeGoesToReviewAndKeepsOldText() throws Exception {
        String token = merchant("12600161002", "门面测试·命中");
        String merchantNo = merchantNoOf(token);

        // 先存一条干净的
        saveNotice(token, "今日到货：土鸡蛋");
        // 再存一条命中的
        saveNotice(token, "全网第一低价，最低价保证");

        // 店铺页仍是旧公告 —— 不是空白
        mvc().perform(get("/biz/store").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.announcement").value("今日到货：土鸡蛋"));

        String bd = opsLogin("bd", "bd123");
        String body = mvc().perform(get("/ops/stores/audits")
                        .header("Authorization", "Bearer " + bd)
                        .param("status", "PENDING"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data").get("records");
        JsonNode mine = null;
        for (JsonNode r : rows) {
            if (merchantNo.equals(r.get("merchantNo").asString())) {
                mine = r;
            }
        }
        assertThat(mine).as("命中的公告必须进人审队列").isNotNull();
        // 人审要看到「机器为什么标它」，否则只能凭感觉判
        assertThat(mine.get("hits").toString()).contains("最低价");
    }

    @Test
    @DisplayName("★ 审核通过之后，内容这时才真正生效")
    void passedNoticeTakesEffect() throws Exception {
        String token = merchant("12600161003", "门面测试·通过");
        String merchantNo = merchantNoOf(token);
        saveNotice(token, "老板说这是最低价");

        String bd = opsLogin("bd", "bd123");
        String auditNo = pendingAuditOf(bd, merchantNo);
        mvc().perform(post("/ops/stores/audits/" + auditNo + "/decide")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/store").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.announcement").value("老板说这是最低价"));
    }

    @Test
    @DisplayName("★ 驳回必须写原因 —— 它原样出现在商家 B 端，不写商家不知道改什么")
    void rejectNeedsReason() throws Exception {
        String token = merchant("12600161004", "门面测试·驳回");
        String merchantNo = merchantNoOf(token);
        saveNotice(token, "全网第一");

        String bd = opsLogin("bd", "bd123");
        String auditNo = pendingAuditOf(bd, merchantNo);
        mvc().perform(post("/ops/stores/audits/" + auditNo + "/decide")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false,\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));

        // 裁完是终态，不能再裁一次（同一条公告不该有两个结论）
        mvc().perform(post("/ops/stores/audits/" + auditNo + "/decide")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false,\"reason\":\"违反广告法极限词\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/stores/audits/" + auditNo + "/decide")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":true}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("★ 覆盖社区移除后再加回来不能撞唯一键 —— 逻辑删的行还占着索引位")
    void removedCommunityCanBeAddedBack() throws Exception {
        String token = merchant("12600161005", "门面测试·社区来回改");

        // ① 仅本社区，覆盖 CM001
        assertThat(json.readTree(saveScope(token, "COMMUNITY", "[\"CM001\"]"))
                .get("code").asInt()).isZero();
        // ② 改成全市：CM001 被逻辑删除（deleted=1），但它仍占着 uk_entity_community 的索引位
        assertThat(json.readTree(saveScope(token, "CITY", "[]")).get("code").asInt()).isZero();
        /*
         * ③ 再改回仅本社区、还是 CM001。
         *
         * 修复前这里是 DuplicateKeyException → 10500「系统开小差了」，
         * 而商家做的只是把经营范围改回去。差集比对挡得住「同一次保存里重复加」，
         * 挡不住「先移除、之后又加回来」—— 那一行 selectList 查不到（被逻辑删过滤掉），
         * 于是被当成新增去 insert，直接撞唯一键。
         *
         * 同一个坑在门店角色、商品社区池上都修过（各有一个 revive），
         * 商家社区表是漏掉的第三处 —— 2026-08-11 的 E2E 把它撞了出来。
         */
        String again = saveScope(token, "COMMUNITY", "[\"CM001\"]");
        assertThat(json.readTree(again).get("code").asInt()).isZero();
        assertThat(json.readTree(again).get("data").get("serviceCommunityNos").toString())
                .as("复活之后覆盖关系要真的回来，不能只是没报错").contains("CM001");
    }

    private String saveScope(String token, String scope, String communityNosJson) throws Exception {
        return mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"营业中\",\"openHours\":\"08:00-20:00\","
                                + "\"address\":\"文一西路 1 号\",\"featured\":[],"
                                + "\"serviceScope\":\"" + scope + "\","
                                + "\"serviceCommunityNos\":" + communityNosJson + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private void saveNotice(String token, String text) throws Exception {
        mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"" + text + "\",\"openHours\":\"08:00-20:00\","
                                + "\"address\":\"文一西路 1 号\",\"featured\":[],"
                                + "\"serviceScope\":\"COMMUNITY\",\"serviceCommunityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String pendingAuditOf(String opsToken, String merchantNo) throws Exception {
        String body = mvc().perform(get("/ops/stores/audits")
                        .header("Authorization", "Bearer " + opsToken).param("status", "PENDING"))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (merchantNo.equals(r.get("merchantNo").asString())) {
                return r.get("auditNo").asString();
            }
        }
        throw new AssertionError("没有找到 " + merchantNo + " 的待审公告");
    }

    // ---------------------------------------------------------------- 社区与自提点（P-2.1/2.2）

    @Test
    @DisplayName("★ 关城只停获客 —— C 端搜不到了，但已有订单不受影响")
    void closingCommunityStopsDiscoveryNotFulfilment() throws Exception {
        String ops = opsLogin("admin", "admin123");

        mvc().perform(post("/ops/communities/C0001/open")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"opened\":false}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.opened").value(false));

        // 开回去，免得影响别的用例（社区是全局资源）
        mvc().perform(post("/ops/communities/C0001/open")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"opened\":true}"))
                .andExpect(jsonPath("$.data.opened").value(true));
    }

    @Test
    @DisplayName("★ 围栏半径不能是 0 —— 0 等于这个社区谁也覆盖不到，而界面上像「还没配」")
    void fenceRadiusCannotBeZero() throws Exception {
        String ops = opsLogin("admin", "admin123");
        mvc().perform(post("/ops/communities/C0001/fence")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fenceRadius\":0}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/communities/C0001/fence")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fenceRadius\":800}"))
                .andExpect(jsonPath("$.data.fenceRadius").value(800));
    }

    @Test
    @DisplayName("★ 邻里自提点不能设服务费 —— 给了报酬他就变成团长了（ADR-005）")
    void neighborPickupMustStayUnpaid() throws Exception {
        String ops = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/pickups").header("Authorization", "Bearer " + ops)
                        .param("type", "NEIGHBOR"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data").get("records");
        if (rows.isEmpty()) {
            return;   // 种子里没有邻里点时跳过，不伪造数据
        }
        String pickupNo = rows.get(0).get("pickupNo").asString();
        mvc().perform(post("/ops/pickups/" + pickupNo + "/service-fee")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceFeeRate\":50}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★ 自提点有「迁移中」这个中间态 —— 没有它，换点时旧点的存量货没人能核销")
    void pickupHasMigratingState() throws Exception {
        String ops = opsLogin("admin", "admin123");
        mvc().perform(post("/ops/pickups/PP0001/status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MIGRATING\"}"))
                .andExpect(jsonPath("$.data.status").value("MIGRATING"));
        // 迁移完成后只能停用：旧点不再启用，新点是另一条记录
        mvc().perform(post("/ops/pickups/PP0001/status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(jsonPath("$.code").value(20004));
        mvc().perform(post("/ops/pickups/PP0001/status")
                .header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"));
    }

    @Test
    @DisplayName("社区列表带自提点数量 —— 列表直接给，避免逐行再查一次")
    void communityListCarriesPickupCount() throws Exception {
        String ops = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/communities").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data").get("records");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("pickupCount").isNumber()).isTrue();
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
        // 分页端点：真正的行在 records 里
        return json.readTree(body).get("data").get("records");
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

    @Test
    @DisplayName("★★★ 类目规格绑定能改第二次 —— 软删挡住重插，运营改一次就 500")
    void categoryBindingsCanBeSavedTwice() throws Exception {
        String ops = opsLogin("admin", "admin123");

        // 自己造维度与取值：测试库走 schema-test.sql 不跑 Flyway，没有 V196 那份种子
        String dimBody = mvc().perform(post("/ops/spec-dims")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TWICE\",\"name\":\"存两遍\",\"valueType\":\"ENUM\","
                                + "\"usageType\":\"SALE\",\"universal\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String dimNo = json.readTree(dimBody).get("data").get("dimNo").asString();

        String v1 = newValue(ops, dimNo, "A");
        String v2 = newValue(ops, dimNo, "B");

        /*
         * **自己建一个类目**，不借用 CAT110。整份替换会把那个类目原有的绑定冲掉，
         * 而测试库是同一个 H2：SpecLibraryCoverageTest 紧接着就在同一份数据上
         * 验「每个类目恰好一个主维度」，被冲掉的话它会红在一个与自己毫无关系的地方。
         */
        String catBody = mvc().perform(post("/ops/categories")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"存两遍专用\",\"parentNo\":\"CAT100\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String categoryNo = json.readTree(catBody).get("data").get("categoryNo").asString();
        String body = "[{\"dimNo\":\"" + dimNo + "\",\"primary\":true,\"required\":false,"
                + "\"valueNos\":[\"" + v1 + "\",\"" + v2 + "\"],\"labels\":{}}]";

        /*
         * 存两遍，第二遍一个字段都没改。**就这样也会炸**：
         * 「整份替换」先删后插，而删若走 @TableLogic 的软删（UPDATE deleted=1），
         * 唯一键 uk_cat_spec(tenant_no, category_no, dim_no) 不含 deleted ——
         * 第二步 INSERT 立刻撞上刚软删的那一行，报 Duplicate entry。
         *
         * 第一遍从不报错，所以这个缺陷一直躲在「运营点第二次保存」背后：
         * 种子是迁移直接 INSERT 的，压根不走这条路。
         */
        for (int round = 1; round <= 2; round++) {
            String r = mvc().perform(post("/ops/category-specs/" + categoryNo)
                            .header("Authorization", "Bearer " + ops)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(r).get("code").asInt())
                    .as("第 %d 次保存失败：%s", round, r)
                    .isEqualTo(0);
        }

        // 存两遍不该存成两份 —— 那是「没删干净」的另一种死法
        String after = mvc().perform(get("/ops/category-specs")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(after).get("data")) {
            if (categoryNo.equals(r.get("categoryNo").asString())) {
                assertThat(r.get("dims").size())
                        .as("存两遍之后维度数不是 1：整份替换没替干净")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("★★★ 建品页的规格来自规格库 —— 本店叫法与停用的档位当场生效")
    void goodsPageReadsSpecLibraryWithMerchantOverride() throws Exception {
        String ops = opsLogin("admin", "admin123");

        // 自己造一套维度/取值/类目：测试库走 schema-test.sql 不跑 Flyway，没有 V196 的种子
        String dimBody = mvc().perform(post("/ops/spec-dims")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"LNKPORT\",\"name\":\"联动份量\",\"valueType\":\"ENUM\","
                                + "\"usageType\":\"SALE\",\"universal\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String dimNo = json.readTree(dimBody).get("data").get("dimNo").asString();
        String half = newValue(ops, dimNo, "500g");
        String jin = newValue(ops, dimNo, "1斤");

        String catBody = mvc().perform(post("/ops/categories")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"规格联动专用\",\"parentNo\":\"CAT100\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String categoryNo = json.readTree(catBody).get("data").get("categoryNo").asString();

        mvc().perform(post("/ops/category-specs/" + categoryNo)
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"dimNo\":\"" + dimNo + "\",\"primary\":true,\"required\":false,"
                                + "\"valueNos\":[\"" + half + "\",\"" + jin + "\"],\"labels\":{}}]"))
                .andExpect(jsonPath("$.code").value(0));

        String biz = merchant("13700001234", "规格联动店");

        /*
         * 第一次读：应当拿到**规格库**那一份 —— 带主维度标记、两个档位。
         * 走旧模板表的话这里是空的（那张表里没有这个类目的模板），
         * 而建品页会退回按品类的泛推荐 —— 正是这次要修的。
         */
        String first = mvc().perform(get("/biz/spec-templates?categoryNo=" + categoryNo)
                        .header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].templateNo").value(dimNo))
                .andExpect(jsonPath("$.data[0].primary").value(true))
                .andExpect(jsonPath("$.data[0].name").value("联动份量"))
                .andExpect(jsonPath("$.data[0].options.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(first.contains("500g"));

        /*
         * 本店改口径：叫「份量」，并且不卖 500g 这一档。
         * **这两件事此前只影响「我的规格」那一页** —— 建品页照旧显示平台原样，
         * 商家会以为自己白设了。
         */
        mvc().perform(post("/biz/spec-override/" + categoryNo)
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dims\":[{\"dimNo\":\"" + dimNo + "\",\"enabled\":true,"
                                + "\"label\":\"份量\",\"values\":[{\"code\":\"500g\",\"enabled\":false},"
                                + "{\"code\":\"1斤\",\"enabled\":true}]}]}"))
                .andExpect(jsonPath("$.code").value(0));

        // 再读一次：名字换成本店叫法，停掉的那一档整个不下发（不是带个 false 让端上滤）
        String after = mvc().perform(get("/biz/spec-templates?categoryNo=" + categoryNo)
                        .header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("份量"))
                .andExpect(jsonPath("$.data[0].options.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(after.contains("500g"),
                "本店停掉的档位不该再下发到建品页");
    }

    private String newValue(String ops, String dimNo, String label) throws Exception {
        String b = mvc().perform(post("/ops/spec-values")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimNo\":\"" + dimNo + "\",\"code\":\"" + label
                                + "\",\"label\":\"" + label + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(b).get("data").get("valueNo").asString();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
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
