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

/**
 * 门店经营类目 —— 货架（TDD-品类约束全链路 §三、§四）。
 *
 * <p>这组用例守的是「三张表各自自洽、合起来不闭」的那类缺口：
 * 平台批的证（{@code mch_entity.category_codes}）、商家摆的货架
 * （{@code mch_store_category}）、商品挂的类目（{@code prd_goods.category_no}），
 * 三者之间的两道闸必须同时成立，否则会出现
 * 「店铺页里看不到、商品列表里还在」这种<b>两个页面对同一批货给出相反答案</b>的状态。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreCategoryFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 类目不在本店经营类目里就拒，且经营类目不被撑大（TDD-门店经营类目 · 取代原「建品自动加入」）")
    void savingGoodsOutsideStoreIsRejected() throws Exception {
        String token = merchant("12600141001", "货架测试·经营类目");
        String storeNo = defaultStore(token);

        // 新店经营类目是空的，且这不是错误状态：他还没开过类目
        assertThat(categories(token, storeNo)).isEmpty();

        String rejected = saveGoodsBody(token, "抽纸一提", "CAT210");
        assertThat(codeOf(rejected)).as("不在经营类目里：拒，而不是像从前那样悄悄加进去").isEqualTo(70068);
        assertThat(json.readTree(rejected).get("msg").asString())
                .as("报错要说出缺的是哪一类，不然他不知道去加哪个").contains(platformName("CAT210"));
        assertThat(categories(token, storeNo)).as("★ 被拒的那一下不能把经营类目撑大").isEmpty();

        // 先开类目，再建商品 —— 这才是现在的路
        open(token, storeNo, "CAT210");
        saveGoods(token, "抽纸一提", "CAT210");

        JsonNode rows = categories(token, storeNo);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("categoryNo").asString()).isEqualTo("CAT210");
        // 名字要拿得到 —— 只回编号的话「我的类目」页是一屏 CAT210
        assertThat(rows.get(0).get("name").asString()).isNotBlank();
        assertThat(rows.get(0).get("goodsCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 底下还有商品的货架撤不掉 —— 否则店铺页里消失、商品列表里还在")
    void shelfInUseCannotBeRemoved() throws Exception {
        String token = merchant("12600141002", "货架测试·占用");
        String storeNo = defaultStore(token);
        open(token, storeNo, "CAT210");
        saveGoods(token, "洗洁精", "CAT210");

        // 整份替换成空 = 把 CAT210 撤掉
        assertThat(codeOf(replace(token, storeNo, "{\"items\":[]}"))).isEqualTo(80008);

        // 撤不掉，货架还在
        assertThat(categories(token, storeNo)).hasSize(1);
    }

    @Test
    @DisplayName("★ 归档类目摆不上货架，也建不了新品 —— 降二级后三级类目全归档，这个口子现在就能踩")
    void archivedCategoryIsRejectedEverywhere() throws Exception {
        String token = merchant("12600141003", "货架测试·归档");
        String storeNo = defaultStore(token);

        // CAT111（原三级叶菜）已被 V168 归档
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT111\"}]}"))).isEqualTo(80007);

        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"归档类目的菜\",\"subtitle\":\"测试\","
                                + "\"categoryNo\":\"CAT111\",\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(codeOf(body)).isEqualTo(80007);
    }

    /**
     * 平台开关：类目闸门开不开。**默认关**（只展示、不限制），所以要测「拦得对不对」
     * 就得在用例里显式打开 —— 跟着默认值走的话，这条用例会在闸门关着时静静地
     * "通过"，而它什么都没验到。
     */
    @Autowired
    private ai.neargo.shop.platform.PlatformConfigService platformConfig;

    private void gate(boolean on) {
        platformConfig.saveFeatureFlag("category.gate.enforce", on, 0, "TEST");
    }

    @org.junit.jupiter.api.AfterEach
    void closeGate() {
        gate(false);   // 复位成生产默认，免得影响同一个上下文里的别的用例
    }

    @Test
    @DisplayName("★★★ 闸门关着时照样能摆 —— 这是生产默认，改回 true 会让商家全线摆不上货架")
    void shelfIsNotBlockedWhileGateIsOff() throws Exception {
        String token = merchant("12600141007", "货架测试·闸门关");
        String storeNo = defaultStore(token);

        /*
         * CAT110 蔬菜要 FRESH_VEG，这个新商家一张证都没有。**闸门关着就该放行** ——
         * 受理入口刚铺开，存量商家的授权码还在补，这时候拦住的不是无证经营，
         * 是平台自己还没建好的那条路。
         *
         * 有人把默认改回 true 的话，症状不是报错，是**商家突然全线摆不上货架**，
         * 而那时没人会想到是一个开关。
         */
        gate(false);
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT110\"}]}"))).isZero();
    }

    @Test
    @DisplayName("★ 没那张证就摆不上带门槛的货架 —— 而且要在勾选那一刻拒，不是等到上架")
    void gatedCategoryNeedsTheCode() throws Exception {
        String token = merchant("12600141004", "货架测试·门槛");
        String storeNo = defaultStore(token);
        /*
         * **门店经营模式故意不动，保持建表默认的 SELF_OPERATED** —— 入驻这条路不改它，
         * 线上每一家第三方新店都是这个样子。免资质要看主体的 self_operated（这家是 0），
         * 按门店模式判的话这里会被放过去（TDD-门店经营类目 §10 要堵的就是这个洞）。
         */
        assertThat(jdbc.queryForObject("select business_mode from mch_store where store_no=?",
                String.class, storeNo)).isEqualTo("SELF_OPERATED");
        assertThat(selfOperatedOf(token, storeNo)).isFalse();

        /*
         * **摆货架这条路此前不受任何开关控制。**「暂时别拦资质」那一轮只接了商品上架，
         * 漏了这里 —— 于是出现过「同一个类目，商品能上架却摆不上货架」这种说不通的状态。
         * 现在两条读同一个开关，所以这条用例要自己打开它。
         */
        gate(true);

        // CAT110 蔬菜要 FRESH_VEG（V168 把门槛从三级上移到了这里）
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT110\"}]}"))).isEqualTo(70002);

        // 无门槛的照旧能摆，且显示名是「皮」—— categoryNo 不变，跨店聚合不受影响
        String ok = replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT210\",\"displayName\":\"日杂\"}]}");
        assertThat(codeOf(ok)).isZero();
        JsonNode rows = json.readTree(ok).get("data");
        assertThat(rows.get(0).get("name").asString()).isEqualTo("日杂");
        assertThat(rows.get(0).get("platformName").asString()).isNotEqualTo("日杂");
    }

    @Test
    @DisplayName("★★ 移出再加回同一个类目 —— 逻辑删除留下的行不能把唯一键占死")
    void removedCategoryCanBeAddedBack() throws Exception {
        String token = merchant("12600141020", "货架测试·加回");
        String storeNo = defaultStore(token);

        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT210\"}]}"))).isZero();
        // 移出（底下没有商品，允许）
        assertThat(codeOf(replace(token, storeNo, "{\"items\":[]}"))).isZero();
        assertThat(categories(token, storeNo)).isEmpty();

        /*
         * 再加回来。删是**逻辑删除**（deleted=1），而唯一键是 (store_no, category_no) 不含这一列 ——
         * 直接 INSERT 会撞唯一键，端上只看到「系统开小差了」。2026-09-20 线上真实发生。
         */
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT210\",\"displayName\":\"日杂\"}]}"))).isZero();
        JsonNode rows = categories(token, storeNo);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("categoryNo").asString()).isEqualTo("CAT210");
        assertThat(rows.get(0).get("name").asString()).isEqualTo("日杂");
        // 复活的是同一行，不是又插了一条
        assertThat(jdbc.queryForObject(
                "select count(*) from mch_store_category where store_no=? and category_no='CAT210'",
                Integer.class, storeNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 运营授码之后，同一个类目就加得上了 —— 没证的第三方的出路在运营那一侧")
    void opsGrantOpensTheCategory() throws Exception {
        /*
         * 号段避开 12600141009 —— 那个号在 BizDashboardAndReviewFlowTest 里被当作
         * 「不是商家的人」，这里注册成商家会让那条用例在全量跑时变红（而报错与类目无关）
         */
        String token = merchant("12600141019", "货架测试·授权后");
        String storeNo = defaultStore(token);
        gate(true);

        // 没证：加不上，也就建不了这一类的商品（TDD-门店经营类目 §11）
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT110\"}]}"))).isEqualTo(70002);

        // 运营在「类目授权」里授码 —— 这就是「增加 / 放行」的那一下，带 reason 留痕
        String bd = opsLogin("bd", "bd123");
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/ops/merchants/" + merchantNoOf(token) + "/auth-codes")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"FRESH_VEG\"],\"reason\":\"已核验食品经营许可证\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT110\"}]}"))).isZero();
        assertThat(categories(token, storeNo)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 平台自营主体加经营类目不判资质 —— 与上面那条同一个类目、同一个开关，只差主体的 self_operated")
    void selfOperatedStoreSkipsQualification() throws Exception {
        String token = merchant("12600141008", "货架测试·自营");
        String storeNo = defaultStore(token);
        markEntitySelfOperated(storeNo);
        // 门店模式故意设成第三方：判据只看主体，门店这一列不参与
        jdbc.update("update mch_store set business_mode='THIRD_PARTY' where store_no=?", storeNo);
        assertThat(selfOperatedOf(token, storeNo)).isTrue();
        gate(true);

        // CAT110 蔬菜要 FRESH_VEG，这家一张证都没有 —— 第三方会被 70002 拒，自营直接加上
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT110\"}]}"))).isZero();
        assertThat(categories(token, storeNo)).hasSize(1);

        // 「类目必须启用」自营照判：归档的谁都不该再摆
        assertThat(codeOf(replace(token, storeNo,
                "{\"items\":[{\"categoryNo\":\"CAT110\"},{\"categoryNo\":\"CAT111\"}]}"))).isEqualTo(80007);
    }

    @Test
    @DisplayName("★ 建第二家店默认复制默认店的货架 —— 分店卖的多半是同一批货")
    void secondStoreCopiesTheFirst() throws Exception {
        String token = merchant("12600141005", "货架测试·分店");
        // 默认套餐只给一家店 —— 不升级的话第二家卡在额度上，测不到货架这件事
        ai.neargo.shop.support.TestPlan.grantPro(planMapper, merchantNoOf(token));
        String first = defaultStore(token);
        assertThat(codeOf(replace(token, first,
                "{\"items\":[{\"categoryNo\":\"CAT210\"}]}"))).isZero();

        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"二店\",\"address\":\"某路 2 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String second = json.readTree(body).get("data").get("storeNo").asString();

        JsonNode rows = categories(token, second);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("categoryNo").asString()).isEqualTo("CAT210");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode categories(String token, String storeNo) throws Exception {
        String body = mvc().perform(get("/biz/store/" + storeNo + "/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String replace(String token, String storeNo, String content) throws Exception {
        return mvc().perform(post("/biz/store/" + storeNo + "/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andReturn().getResponse().getContentAsString();
    }

    private int codeOf(String body) {
        return json.readTree(body).get("code").asInt();
    }

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String defaultStore(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(0).get("storeNo").asString();
    }

    /** 给这家店开一个经营类目（在现有的基础上加一个） */
    private void open(String token, String storeNo, String categoryNo) throws Exception {
        StringBuilder items = new StringBuilder();
        for (JsonNode r : categories(token, storeNo)) {
            items.append("{\"categoryNo\":\"").append(r.get("categoryNo").asString()).append("\"},");
        }
        items.append("{\"categoryNo\":\"").append(categoryNo).append("\"}");
        assertThat(codeOf(replace(token, storeNo, "{\"items\":[" + items + "]}"))).isZero();
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 把这家店的主体标成平台自营（生产上只有 POST /ops/merchants/self-operated 能写这一位） */
    private void markEntitySelfOperated(String storeNo) {
        jdbc.update("update mch_entity set self_operated=1 where entity_no="
                + "(select entity_no from mch_store where store_no=?)", storeNo);
    }

    /** B 端门店列表里这家店的 selfOperated —— 面板标不标「需资质」读的就是它 */
    private boolean selfOperatedOf(String token, String storeNo) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : json.readTree(body).get("data")) {
            if (storeNo.equals(s.get("storeNo").asString())) {
                return s.get("selfOperated").asBoolean();
            }
        }
        throw new AssertionError("门店列表里没有 " + storeNo);
    }

    @Autowired
    private ai.neargo.shop.product.service.CategoryService categoryService;

    private String platformName(String categoryNo) {
        for (var lv1 : categoryService.tree()) {
            for (var lv2 : lv1.children()) {
                if (categoryNo.equals(lv2.categoryNo())) {
                    return lv2.name();
                }
            }
        }
        throw new AssertionError("类目树里没有 " + categoryNo);
    }

    private String saveGoodsBody(String token, String title, String categoryNo) throws Exception {
        return mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subtitle\":\"测试\","
                                + "\"categoryNo\":\"" + categoryNo + "\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andReturn().getResponse().getContentAsString();
    }

    private void saveGoods(String token, String title, String categoryNo) throws Exception {
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subtitle\":\"测试\","
                                + "\"categoryNo\":\"" + categoryNo + "\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 注册消费者 → 提交入驻 → BD 审核通过 → 重新登录拿到带商家身份的 token */
    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
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
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
