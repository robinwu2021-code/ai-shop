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
        token = login(phone);   // 成员关系变了，重新登录拿新作用域

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
        return login(phone);
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
