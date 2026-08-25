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
 * 无证照先开店（线 A）：建店不以拿到营业执照为前置。
 *
 * <p>两件事必须同时成立，缺一件这个功能就是有害的：
 * <ul>
 *   <li><b>店真的开起来了</b> —— 有主体、有默认门店、有 B 端身份，能进经营台干活</li>
 *   <li><b>买家看不到</b> —— 没证照的店的货不该进任何人的可见范围。
 *       只做前一件的话，等于「谁都能建一家能卖货的店」，比不做还糟</li>
 * </ul>
 *
 * <p>可见性那条是本组的重点：撤掉 {@code reachableCommunities} 里的 status 判断，
 * {@link #shellIsInvisibleToBuyers} 必须立刻变红。
 */
@SpringBootTest
@ActiveProfiles("test")
class QuickStartFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper accountMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 没有证照也能把店开起来：主体 + 默认门店 + B 端身份，一次到位")
    void quickStartOpensARealShop() throws Exception {
        String token = login("12600160001");

        String body = mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"巷口小卖部\",\"address\":\"文三路 5 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode profile = json.readTree(body).get("data");
        assertThat(profile.get("merchantNo").asString()).as("当场就有主体号").isNotBlank();
        assertThat(profile.get("name").asString()).isEqualTo("巷口小卖部");
        /*
         * status 是 B 端唯一的「我现在能不能干活」判据（见 MerchantProfileVO）。
         * 这里断言的是它**不是** ACTIVE —— 具体叫什么由端上文案决定，
         * 但绝不能是 ACTIVE，那会让 b-app 以为已经开张了。
         */
        assertThat(profile.get("status").asString()).isNotEqualTo("ACTIVE");

        // 有默认门店 —— 少了它，他登录进来一家店都没有，「开店」这件事没发生
        String stores = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(stores).get("data")).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 没证照的店对买家完全不可见 —— 撤掉可见性闸门这条必红")
    void shellIsInvisibleToBuyers() throws Exception {
        String token = login("12600160002");
        mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"还没交执照的店\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String merchantNo = merchantNoOf(token);

        /*
         * **先把这家店摆成「不看状态就一定可见」的样子**，否则这条用例是假阳性。
         *
         * 刚建出来的占位主体没有任何社区覆盖，本来就落进「范围空 + 只有自提 = 谁也看不到」
         * 那一支 —— 闸门在不在，返回的都是空列表。第一版就是这么写的，
         * 撤掉闸门跑一遍照样绿，等于什么都没测到。
         *
         * 改成把 fulfillment_reach 设成 SHIPPING（快递没有履约半径 → 全部开放社区）：
         * 这时只有状态那一道闸拦得住它。撤掉闸门这条立刻变红 —— 已实测。
         */
        var shell = entityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, merchantNo));
        shell.setFulfillmentReach("SHIPPING");
        entityMapper.updateById(shell);

        /*
         * reachableCommunities 是 C 端可见性的**唯一出口** —— 上架写社区池、
         * 商家详情可达性、履约全都只认它。空列表 = 这家店的货进不了任何人的可见范围。
         *
         * 这条断言挡住的是一个很具体的坏结果：谁都能注册一个账号、建一家店、
         * 把货铺给买家，而平台从没核过他是谁。
         */
        assertThat(merchantQueryPort.reachableCommunities(merchantNo))
                .as("没证照的主体不该对任何社区可见 —— 哪怕它的履约方式本来覆盖全部社区")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 连点两次只会有一家店 —— 不能建出两个永远补不齐的空壳")
    void quickStartIsIdempotent() throws Exception {
        String token = login("12600160003");
        String first = quickStart(token, "手抖点了两次的店");
        String second = quickStart(token, "手抖点了两次的店");

        assertThat(second).as("第二次返回的是同一个主体").isEqualTo(first);
        assertThat(entityMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchEntity::getOwnerUserNo, userNoOf(token))))
                .as("库里只有一个主体").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 店名必填 —— 没名字的店在列表里就是一行空白，谁也认不出是哪家")
    void storeNameIsRequired() throws Exception {
        String token = login("12600160004");
        mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★★ 已激活的商家不受影响 —— 闸门只挡没证照的，不能误伤正常营业的店")
    void activeMerchantStaysVisible() throws Exception {
        /*
         * 这一条是可见性闸门的**反向验证**。加一行 `if (!ACTIVE.equals(status))`
         * 很容易顺手把正常商家也挡掉，而那种故障没有任何报错 ——
         * 只是全平台的货突然对谁都不可见了。
         */
        String token = merchantViaAudit("12600160005", "正常营业的店");
        String merchantNo = merchantNoOf(token);

        assertThat(merchantQueryPort.reachableCommunities(merchantNo))
                .as("审核通过的商家照常可见").isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 补证照：店与货原样留着，从此对买家可见 —— 不是另开一家新店")
    void addingLicenseUpgradesTheSameShop() throws Exception {
        String token = login("12600160006");
        String merchantNo = quickStart(token, "先开着的店");

        /*
         * 在占位主体下先干点活 —— 这正是「先开店」的意义所在。
         * 补完证照如果这些东西不在了，等于让他白干一场。
         */
        String storeNo = json.readTree(mvc().perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("storeNo").asString();

        // 此刻买家看不到
        assertThat(merchantQueryPort.reachableCommunities(merchantNo)).isEmpty();

        // 补证照：走的就是普通入驻申请，端上不需要知道「认领」这回事
        String body = mvc().perform(post("/biz/merchant/apply").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"先开着的店（有限公司）\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isNotBlank();

        String applyNo = json.readTree(mvc().perform(get("/biz/merchant/apply")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * **同一个主体，不是新建的**。这条断言是整个「先开店后补证照」成不成立的关键：
         * 认领没做的话这里会是一个新主体号，而他先开的那家店还留在旧主体下 ——
         * 两家店，他只认得出一家，且有货的那家永远不可见。
         */
        assertThat(merchantNoOf(token)).as("补证照升级的是同一个主体").isEqualTo(merchantNo);

        String storesAfter = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(storesAfter).get("data")).as("没有多出一家店").hasSize(1);
        assertThat(json.readTree(storesAfter).get("data").get(0).get("storeNo").asString())
                .as("还是原来那家店").isEqualTo(storeNo);

        // 从此对买家可见 —— 这才是补证照换来的东西
        assertThat(merchantQueryPort.reachableCommunities(merchantNo))
                .as("补完证照就该被买家看到").isNotEmpty();

        // 升级时要把执照上的正式名称与法律形态补上（快速开店时这两项是空的）
        var upgraded = entityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, merchantNo));
        assertThat(upgraded.getStatus()).isEqualTo("ACTIVE");
        assertThat(upgraded.getName()).isEqualTo("先开着的店（有限公司）");
        assertThat(upgraded.getLegalForm()).as("进件要用它，不能留空").isNotBlank();
        assertThat(upgraded.getVerified()).as("审核通过才带认证标").isTrue();
    }

    @Test
    @DisplayName("★★ 证照数量上限：到顶之后建不出第 6 个主体")
    void entityQuotaIsEnforced() throws Exception {
        String token = login("12600160007");
        String userNo = userNoOf(token);

        /*
         * 直接造 5 个 owner 成员行 + 主体 —— 走真实入驻链路要五轮「申请 → 审核」，
         * 而这条用例验的是**数量闸本身**，不是入驻流程。
         */
        for (int i = 0; i < 5; i++) {
            var e = new ai.neargo.shop.merchant.entity.MchEntity();
            e.setEntityNo("E-QUOTA-" + i + "-" + System.nanoTime());
            e.setName("已有证照 " + i);
            e.setStatus("ACTIVE");
            e.setOwnerUserNo(userNo);
            entityMapper.insert(e);

            var a = new ai.neargo.shop.merchant.entity.MchAccount();
            a.setMchAccountNo("MA-QUOTA-" + i + "-" + System.nanoTime());
            a.setEntityNo(e.getEntityNo());
            a.setUserNo(userNo);
            a.setIsOwner(true);
            a.setIsPrimary(false);
            a.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
            accountMapper.insert(a);
        }

        mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"第六张证照\"}"))
                .andExpect(jsonPath("$.code").value(70037));
    }

    // ---------------------------------------------------------------- helpers

    private String quickStart(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    /** 走完整的「申请 → 审核通过」，拿到一个 ACTIVE 主体 */
    private String merchantViaAudit(String phone, String name) throws Exception {
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
