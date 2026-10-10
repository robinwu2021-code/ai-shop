package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
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
 * 多证照身份解析（线 B · B1）：<b>「他现在是哪个主体」的答案来自「他现在站在哪家店里」</b>。
 *
 * <p>改造之前解析器固定取默认主体，切门店只在解析<b>之后</b>把 {@code currentStoreNo}
 * 换掉。单主体时两者等价 —— 这也是它一直没被发现的原因；一旦一个人有两张营业执照，
 * 进 B 主体的店时 {@code merchantNo} 还停在 A，权限、商品、订单全按 A 主体算，
 * <b>页面照常打开，只是数据是另一家的</b>。
 *
 * <p>这一组测两件相反的事，缺一件都不算做完：
 * <ul>
 *   <li>{@link #storeHeaderSwitchesEntity} —— 自己的店，切得过去（功能）</li>
 *   <li>{@link #foreignStoreHeaderCannotCrossEntities} —— 别人的店，切不过去（越权）</li>
 * </ul>
 *
 * <p><b>越权那条是本组的重点</b>：{@code X-Store-No} 是客户端可控的请求头。
 * 把 {@code BizIdentityResolverImpl.membershipFor} 里那个「反查出的主体必须在我的
 * 成员关系里」的 filter 去掉，{@link #foreignStoreHeaderCannotCrossEntities} 必须立刻变红。
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiEntityIdentityFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 一个账号两张执照：带上另一家店的 X-Store-No，主体跟着换")
    void storeHeaderSwitchesEntity() throws Exception {
        String phone = "12600170001";
        String token = merchant(phone, "老王杂货铺");
        String firstEntity = merchantNoOf(token, null);
        String firstStore = defaultStoreNo(token);

        // 第二张执照：快速开店建一个占位主体（他名下第二门生意）
        String secondEntity = quickStart(token, "老王水果店");
        assertThat(secondEntity).isNotEqualTo(firstEntity);
        // A7：这个令牌要打 /biz/**，必须是 btk_
        token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);   // 成员关系变了，重新登录拿新作用域

        String secondStore = defaultStoreNoOf(secondEntity);
        assertThat(secondStore).as("第二张执照名下应当自带一家默认店").isNotBlank();

        /*
         * ★ 不带头 → 默认主体（is_primary）。这一支必须与多证照之前一模一样，
         * 否则「绝大多数只有一张执照的商家」会被这次改造波及。
         */
        assertThat(merchantNoOf(token, null)).isEqualTo(firstEntity);
        assertThat(merchantNoOf(token, firstStore)).isEqualTo(firstEntity);

        // ★ 带上第二家店 → 主体换成第二张执照。这就是这次改造要的那一件事
        assertThat(merchantNoOf(token, secondStore))
                .as("站在第二张执照的店里，身份就该是第二张执照")
                .isEqualTo(secondEntity);
    }

    @Test
    @DisplayName("★★ 越权：拿别人家真实存在的门店号伪造请求头，只能回落到自己的主体")
    void foreignStoreHeaderCannotCrossEntities() throws Exception {
        String mine = merchant("12600170010", "我的店");
        String myEntity = merchantNoOf(mine, null);

        String others = merchant("12600170011", "别人的店");
        String othersStore = defaultStoreNo(others);
        String othersEntity = merchantNoOf(others, null);
        assertThat(othersEntity).isNotEqualTo(myEntity);

        /*
         * 门店号是**真实存在**的 —— 这一点很重要：查不到的门店号任何实现都会回落，
         * 测不出什么。能测出问题的是「真实存在但不属于我」。
         */
        assertThat(merchantNoOf(mine, othersStore))
                .as("伪造别人家的门店号，身份必须还是我自己的主体")
                .isEqualTo(myEntity);

        /*
         * ★ 不只看 merchantNo：真正会漏数据的是**当前门店**。
         * 身份挡住了但门店没挡住的话，订单、库存这些按 storeNo 查的接口照样出别人的数据。
         */
        JsonNode ctx = ctxOf(mine, othersStore);
        assertThat(ctx.get("currentStoreNo").asString())
                .as("当前门店也不能落到别人家的店上").isNotEqualTo(othersStore);
        var stores = ctx.get("storeNos").valueStream().map(JsonNode::asString).toList();
        assertThat(stores).as("门店集合里不该出现别人家的店").doesNotContain(othersStore);
    }

    @Test
    @DisplayName("★ 门店号查无此店（端上缓存了个旧的）→ 静默回落默认主体，不报错")
    void unknownStoreHeaderFallsBackSilently() throws Exception {
        String token = merchant("12600170020", "缓存了旧门店号的店");
        String entity = merchantNoOf(token, null);

        /*
         * 这里刻意断言 code=0 而不是某个错误码：门店被停用、授权被收回之后，
         * 端上缓存里那个门店号就是查不到了。让整个 App 报错不如把他带回默认店 ——
         * 他要做的只是重新选一次店，而报错会让他以为系统坏了。
         */
        assertThat(merchantNoOf(token, "ST_NOT_EXIST_9999")).isEqualTo(entity);
    }

    // ------------------------------------------------------------ B2：跨证照查询

    @Test
    @DisplayName("★★ 门店切换器一次给出两张证照下的店，且按证照分组")
    void myStoresSpansAllEntities() throws Exception {
        String phone = "12600170030";
        String token = merchant(phone, "分组·第一张");
        String firstEntity = merchantNoOf(token, null);
        String secondEntity = quickStart(token, "分组·第二张");
        // A7：这个令牌要打 /biz/**，必须是 btk_
        token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);

        JsonNode groups = okData(token, "/biz/stores/mine");
        var entityNos = groups.valueStream()
                .map(g -> g.get("entity").get("entityNo").asString()).toList();
        assertThat(entityNos)
                .as("两张证照都要在，且默认那张在前 —— 端上不重排，顺序就是这里给的")
                .containsExactly(firstEntity, secondEntity);

        /*
         * ★ 分组而不是拍平：两家店同名是常事（「文三路店」在两张执照下各有一家）。
         * 拍平之后老板在切换器里看到两个一模一样的条目，点哪个都不知道进了哪张执照 ——
         * 而进错执照的表现是「商品怎么全没了」。
         */
        for (JsonNode g : groups) {
            assertThat(g.get("stores")).as("每张证照下至少有它的默认店").isNotEmpty();
            assertThat(g.get("entity").get("storeCount").asInt())
                    .as("计数与列表长度必须是同一个数，对不上会让人以为有店没显示出来")
                    .isEqualTo(g.get("stores").size());
        }

        // ★ 对照：老接口只看得到当前那一张证照。这正是 /biz/stores/mine 存在的理由
        JsonNode oldList = okData(token, "/biz/store/list");
        assertThat(oldList.size()).as("/biz/store/list 仍然只给当前证照的店").isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 店员的门店切换器只给他被授权的那几家，且他看不到证照管理")
    void staffSeesOnlyGrantedStores() throws Exception {
        String boss = merchant("12600170040", "有两家店的老板");
        String merchantNo = merchantNoOf(boss, null);
        TestPlan.grantQuota(planMapper, merchantNo, 3);
        String secondStore = createStore(boss, "只授权这一家");
        String defaultStore = defaultStoreNo(boss);

        // 招一个店员，只授权他进第二家店
        String staffPhone = "12600170042";
        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + staffPhone + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + secondStore + "\",\"role\":\"CLERK\"}"))
                .andExpect(jsonPath("$.code").value(0));

        // 店员是独立登录（principal 是 mch_account_no，不是 C 端 userNo）
        String staff = TestLogin.merchantStaff(mvc(), json, otpStore, staffPhone);

        JsonNode groups = okData(staff, "/biz/stores/mine");
        var stores = groups.valueStream()
                .flatMap(g -> g.get("stores").valueStream())
                .map(x -> x.get("storeNo").asString()).toList();
        assertThat(stores).as("被授权的那家要在").contains(secondStore);
        assertThat(stores)
                .as("没授权的店一家都不能出现 —— 端上会把它渲染成一个点进去必然 403 的条目")
                .doesNotContain(defaultStore);
        assertThat(groups.get(0).get("entity").get("canManage").asBoolean())
                .as("店员不是这张证照的老板").isFalse();

        /*
         * ★ 证照管理是老板的事：STORE_ADMIN 不在 assignableCodes 里，
         * 自定义角色勾不到它 —— 所以「只有老板能管证照」是结构保证的，不靠文案约束。
         */
        mvc().perform(get("/biz/entities").header("Authorization", "Bearer " + staff))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★★ 越权：拿别人的证照号问详情，403 而不是 404 —— 它存在，只是不属于你")
    void entityDetailRefusesForeignEntities() throws Exception {
        String mine = merchant("12600170050", "详情·我的");
        String myEntity = merchantNoOf(mine, null);
        String others = merchant("12600170051", "详情·别人的");
        String othersEntity = merchantNoOf(others, null);

        assertThat(okData(mine, "/biz/entity/" + myEntity).get("entity").get("entityNo").asString())
                .isEqualTo(myEntity);

        /*
         * 给 404 的话他会以为自己记错了证照号而反复去找 —— 而真正该说的是「这不是你的」。
         * 这一条同时是越权断言：详情里带着门店列表与收款信息，漏出去就是别人家的经营数据。
         */
        String body = mvc().perform(get("/biz/entity/" + othersEntity)
                        .header("Authorization", "Bearer " + mine))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt())
                .as("别人的证照必须拒，且不能是 0").isNotZero();
        assertThat(body).as("拒绝的响应里不该夹带别人家的任何字段")
                .doesNotContain("详情·别人的");
    }

    // ------------------------------------------------------------ B3：既有接口加可选 entityNo

    @Test
    @DisplayName("★★ 老板在证照管理页直接给另一张证照传执照 —— 不用先切到那张证照下的店")
    void entityNoParamTargetsAnotherOwnedEntity() throws Exception {
        String phone = "12600170060";
        String token = merchant(phone, "两张证照·第一张");
        String first = merchantNoOf(token, null);
        String second = quickStart(token, "两张证照·第二张");
        // A7：这个令牌要打 /biz/**，必须是 btk_
        token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);

        // 给第二张证照传一张营业执照。**当前证照仍是第一张** —— 这正是这条参数存在的理由
        mvc().perform(post("/biz/qualifications/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qualType\":\"BUSINESS_LICENSE\",\"qualName\":\"第二张的执照\","
                                + "\"imageUrl\":\"https://x/y.jpg\",\"entityNo\":\"" + second + "\"}"))
                .andExpect(jsonPath("$.code").value(0));

        var onSecond = okData(token, "/biz/qualifications?entityNo=" + second)
                .get("items").valueStream().map(i -> i.get("qualName").asString()).toList();
        assertThat(onSecond).as("传到第二张证照上了").contains("第二张的执照");

        /*
         * ★ 不传参数时必须是原行为（当前证照 = 第一张）。
         * 这一条守的是**存量单证照账号**：他们永远不传这个参数，行为一个字都不能变。
         */
        var onCurrent = okData(token, "/biz/qualifications")
                .get("items").valueStream().map(i -> i.get("qualName").asString()).toList();
        assertThat(onCurrent).as("没传参数就是当前证照，不该看到第二张的证").doesNotContain("第二张的执照");
        assertThat(merchantNoOf(token, null)).as("当前证照没有被这次操作改掉").isEqualTo(first);
    }

    @Test
    @DisplayName("★★ 越权：传别人的 entityNo → 403，而不是静默落到自己的证照上")
    void entityNoParamRefusesForeignEntity() throws Exception {
        String mine = merchant("12600170070", "参数·我的");
        String myEntity = merchantNoOf(mine, null);
        String others = merchant("12600170071", "参数·别人的");
        String othersEntity = merchantNoOf(others, null);

        /*
         * ★ **静默回落是这里最危险的实现**：他以为在给别人（或另一张）证照传证，
         * 实际动的是当前这张，两边都不报错。所以必须是 403，而且不能留下痕迹。
         */
        String body = mvc().perform(post("/biz/qualifications/save")
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qualType\":\"BUSINESS_LICENSE\",\"qualName\":\"越权的执照\","
                                + "\"imageUrl\":\"https://x/y.jpg\",\"entityNo\":\"" + othersEntity + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).as("别人的证照必须拒").isNotZero();

        var onMine = okData(mine, "/biz/qualifications")
                .get("items").valueStream().map(i -> i.get("qualName").asString()).toList();
        assertThat(onMine).as("更不能静默落到我自己的证照上 —— 那是最难发现的一种错")
                .doesNotContain("越权的执照");
        assertThat(merchantNoOf(mine, null)).isEqualTo(myEntity);

        // 收款进件同一条闸
        assertThat(codeOf(get("/biz/merchant/payment?entityNo=" + othersEntity), mine)).isNotZero();
    }

    @Test
    @DisplayName("★★ 建店时挂到另一张证照下 —— 撞的是那张证照的额度，不是当前这张的")
    void createStoreCanTargetAnotherOwnedEntity() throws Exception {
        String phone = "12600170080";
        String token = merchant(phone, "建店·第一张");
        String first = merchantNoOf(token, null);
        String second = quickStart(token, "建店·第二张");
        // A7：这个令牌要打 /biz/**，必须是 btk_
        token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);

        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"挂在第二张下的分店\",\"entityNo\":\"" + second + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String storeNo = json.readTree(body).get("data").get("storeNo").asString();

        // ★ 真的挂在第二张证照下：带上它的门店号解析出来的主体就是第二张
        assertThat(merchantNoOf(token, storeNo)).isEqualTo(second);
        // ★ 当前证照（第一张）的门店列表里不该多出这家店
        var current = okData(token, "/biz/store/list").valueStream()
                .map(x -> x.get("storeNo").asString()).toList();
        assertThat(current).as("它不属于当前证照").doesNotContain(storeNo);
        assertThat(merchantNoOf(token, null)).isEqualTo(first);
    }

    // ------------------------------------------------------------ 脚手架

    /** 带（或不带）X-Store-No 问一次 /biz/context，返回解析出来的主体号。 */
    private String merchantNoOf(String token, String storeNo) throws Exception {
        return ctxOf(token, storeNo).get("merchantNo").asString();
    }

    private JsonNode ctxOf(String token, String storeNo) throws Exception {
        var req = get("/biz/context").header("Authorization", "Bearer " + token);
        if (storeNo != null) {
            req = req.header("X-Store-No", storeNo);
        }
        String body = mvc().perform(req)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private int codeOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                       String token) throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private JsonNode okData(String token, String path) throws Exception {
        String body = mvc().perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String defaultStoreNo(String token) throws Exception {
        return ctxOf(token, null).get("currentStoreNo").asString();
    }

    /**
     * 第二张执照下的默认店。
     *
     * <p><b>刻意直接查库而不是调 {@code /biz/store/list}</b>：那个接口按当前主体作用域查，
     * 而当前主体正是第一张执照 —— 拿它去找第二张执照的店，永远是空。
     * 跨执照查询接口是 B2 的事；在它到位之前，这里查库是唯一诚实的写法。
     * （查库要解除数据域：{@code mch_store} 挂了主体锚点，测试线程没有作用域，
     * 不解除会静默返回空。）
     */
    private String defaultStoreNoOf(String entityNo) {
        var store = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, entityNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getIsDefault, true)
                        .last("limit 1")));
        return store == null ? "" : store.getStoreNo();
    }

    /** 建一家店并返回它的门店号。 */
    private String createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    private String quickStart(String token, String storeName) throws Exception {
        String body = mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"" + storeName + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
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

        String bd = opsLogin();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
