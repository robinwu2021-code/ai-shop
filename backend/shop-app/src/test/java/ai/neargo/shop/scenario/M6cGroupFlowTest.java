package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
 * M6c 团购与求团 —— **用例先行**。
 *
 * <p>ADR-003 决定了这个模块的形态：**报价不做事前审核**，靠三件事兜底 ——
 * ① 选定即**锁价**（之后商家改价不影响这一单）
 * ② 改价**留痕并公示**（涨价尤其）
 * ③ 毁约计入**信用**并公示在报价卡上
 * 三者缺一，「不审核」就变成了「随便报低价钓单再涨价」。
 */
@SpringBootTest
@ActiveProfiles("test")
class M6cGroupFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private MchEntityMapper merchantMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- ADR-003 三件事

    @Test
    @DisplayName("★ 选定即锁价：之后商家改价，已锁的价格不变")
    void chosenQuoteLocksPrice() throws Exception {
        String owner = login("12900129001");
        String requestNo = createRequest(owner, "求团：儿童学步车");
        String biz = loginAsOwnerOf("M0001", "12900129002");

        String quoteNo = quote(biz, requestNo, 19900L, 5, 7);
        choose(owner, requestNo, quoteNo);

        // 商家事后涨价
        revise(biz, quoteNo, 29900L);

        JsonNode data = requestDetail(owner, requestNo);
        assertThat(data.get("status").asString()).isEqualTo("LOCKED");
        // 锁定的是快照价，不是报价表里的当前价 —— 否则「不审核」等于让商家随时改价
        assertThat(data.get("chosenQuote").get("priceMinor").asLong()).isEqualTo(19900L);
    }

    @Test
    @DisplayName("★★ 锁价后：`/quote` 拒、`/revise` 放 —— **两个入口口径不同是有意的**")
    void quoteAndReviseDifferAfterLock() throws Exception {
        String owner = login("12900129021");
        String requestNo = createRequest(owner, "求团：折叠梯");
        String biz = loginAsOwnerOf("M0001", "12900129022");
        String quoteNo = quote(biz, requestNo, 8800L, 3, 7);
        choose(owner, requestNo, quoteNo);

        /*
         * **这条测试存在的理由是防止「顺手统一」** —— 我就差点这么干过。
         *
         * 两个入口做的是同一件事（改价），对「已锁价」的判断却相反：
         *   · quote(requestNo)：直接拒。这张需求单已经选定了别人／已关闭，
         *     再收报价「只会让商家以为还有机会」。
         *   · revise(quoteNo)：放行。锁价保护的是**成交快照**（chosenQuote 里的价），
         *     而这张报价对**后续**邻居仍然有效 —— 见 chosenQuoteLocksPrice。
         *
         * 看起来像不一致，实际是两个问题的两个答案。谁要统一它们，
         * 先让这条红一次，然后回答：统一之后上面那两句话哪一句不再成立。
         */
        mvc().perform(post("/biz/group-request/" + requestNo + "/quote")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPriceMinor\":9900,\"minQty\":3,\"validDays\":7}"))
                .andExpect(jsonPath("$.code").value(20004));

        revise(biz, quoteNo, 9900L);   // 同一件事，这条路放行
    }

    @Test
    @DisplayName("★ 改价必须留痕并公示，涨价单独标记")
    void priceRevisionIsPublic() throws Exception {
        String owner = login("12900129003");
        String requestNo = createRequest(owner, "求团：折叠桌");
        String biz = loginAsOwnerOf("M0001", "12900129004");

        String quoteNo = quote(biz, requestNo, 10000L, 3, 7);
        revise(biz, quoteNo, 12000L);   // 涨价
        revise(biz, quoteNo, 11000L);   // 降价

        String body = mvc().perform(get("/mp/group-request/" + requestNo + "/price-history"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode history = json.readTree(body).get("data");

        assertThat(history).hasSize(2);
        // 藏起改价记录的话，「报低价钓单再涨价」就无人能发现 —— 这是不审核的代价
        assertThat(history.get(0).get("fromPriceMinor").asLong()).isEqualTo(10000L);
        assertThat(history.get(0).get("toPriceMinor").asLong()).isEqualTo(12000L);
        assertThat(history.get(0).get("raised").asBoolean()).isTrue();
        assertThat(history.get(1).get("raised").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★ 报价卡直接公示毁约次数（事后信用替代事前审核）")
    void breachCountIsShownOnQuote() throws Exception {
        setBreachCount("M0002", 2);
        try {
            String owner = login("12900129005");
            String requestNo = createRequest(owner, "求团：米袋");
            String biz = loginAsOwnerOf("M0002", "12900129006");
            quote(biz, requestNo, 5000L, 2, 7);

            JsonNode quotes = quotes(owner, requestNo);
            // 用户在选报价时就要看到「这家毁过约」，而不是事后投诉
            assertThat(quotes.get(0).get("merchant").get("breachCount").asInt()).isEqualTo(2);
            /*
             * 评分与评价数都要下发。**不断言「大于 0」** ——
             * 评分改成派生之后，一家还没人评过的店就是 0 分 0 条，那是真话；
             * 端上按 ratingCount 显示「暂无评价」，而不是挂一排空星当差评店。
             */
            assertThat(quotes.get(0).get("merchant").has("rating")).isTrue();
            assertThat(quotes.get(0).get("merchant").get("ratingCount").asInt()).isZero();
        } finally {
            setBreachCount("M0002", 0);
        }
    }

    // ---------------------------------------------------------------- 求团主流程

    @Test
    @DisplayName("发起求团 → 多商家报价 → 报价对比")
    void createRequestAndCollectQuotes() throws Exception {
        String owner = login("12900129010");
        String requestNo = createRequest(owner, "求团：加厚垃圾袋");

        quote(loginAsOwnerOf("M0001", "12900129011"), requestNo, 3000L, 10, 7);
        quote(loginAsOwnerOf("M0002", "12900129012"), requestNo, 2800L, 20, 7);

        JsonNode data = requestDetail(owner, requestNo);
        assertThat(data.get("status").asString()).isEqualTo("QUOTED");
        /*
         * **报价跟着详情一起下发**（契约 `GroupRequest.quotes`）。
         * 原先这里只有一个 `quoteCount`，两端的页面却都要列报价 ——
         * 于是 `request.quotes.length` 读到 undefined，整页当场崩掉。
         */
        assertThat(data.get("quotes").size()).as("报价要跟着详情一起给").isEqualTo(2);
        assertThat(data.get("quotes").get(0).get("merchant").get("name").asString()).isNotEmpty();

        JsonNode quotes = quotes(owner, requestNo);
        assertThat(quotes).hasSize(2);
        // 报价按单价升序：用户第一眼看到的应该是最便宜的
        assertThat(quotes.get(0).get("priceMinor").asLong()).isEqualTo(2800L);
        assertThat(quotes.get(0).get("minCount").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("+1 是**意向不是订单**：计数变化，但不产生任何交易")
    void interestIsIntentNotOrder() throws Exception {
        String owner = login("12900129013");
        String requestNo = createRequest(owner, "求团：床垫");
        String neighbor = login("12900129014");

        mvc().perform(post("/mp/group-request/" + requestNo + "/interest")
                        .header("Authorization", "Bearer " + neighbor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestedCount").value(1))
                .andExpect(jsonPath("$.data.interested").value(true));

        // 邻居没有任何订单 —— +1 只是表达「我也想要」
        mvc().perform(get("/mp/order").header("Authorization", "Bearer " + neighbor))
                .andExpect(jsonPath("$.data.total").value(0));

        // 再点一次取消
        mvc().perform(post("/mp/group-request/" + requestNo + "/interest")
                        .header("Authorization", "Bearer " + neighbor))
                .andExpect(jsonPath("$.data.interestedCount").value(0))
                .andExpect(jsonPath("$.data.interested").value(false));
    }

    @Test
    @DisplayName("只有发起人能选定报价（ownerId 是团实例上的字段，不是身份）")
    void onlyOwnerCanChoose() throws Exception {
        String owner = login("12900129015");
        String requestNo = createRequest(owner, "求团：校服");
        String quoteNo = quote(loginAsOwnerOf("M0001", "12900129016"), requestNo, 8000L, 5, 7);

        String stranger = login("12900129017");
        mvc().perform(post("/mp/group-request/" + requestNo + "/choose")
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteNo\":\"" + quoteNo + "\"}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("选定后不能再改选（锁价即定局，要换得先关单重发）")
    void cannotChooseTwice() throws Exception {
        String owner = login("12900129018");
        String requestNo = createRequest(owner, "求团：晾衣架");
        String q1 = quote(loginAsOwnerOf("M0001", "12900129019"), requestNo, 5000L, 3, 7);
        String q2 = quote(loginAsOwnerOf("M0002", "12900129020"), requestNo, 4500L, 3, 7);

        choose(owner, requestNo, q1);
        mvc().perform(post("/mp/group-request/" + requestNo + "/choose")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteNo\":\"" + q2 + "\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("已锁价的需求单不接受新报价")
    void lockedRequestRejectsNewQuote() throws Exception {
        String owner = login("12900129021");
        String requestNo = createRequest(owner, "求团：收纳箱");
        String quoteNo = quote(loginAsOwnerOf("M0001", "12900129022"), requestNo, 3000L, 5, 7);
        choose(owner, requestNo, quoteNo);

        String late = loginAsOwnerOf("M0002", "12900129023");
        mvc().perform(post("/biz/group-request/" + requestNo + "/quote")
                        .header("Authorization", "Bearer " + late)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPriceMinor\":2000,\"minQty\":5,\"validDays\":7}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("同一商家对同一需求单只有一条报价（改价是改它，不是新开一条）")
    void oneQuotePerMerchantPerRequest() throws Exception {
        String owner = login("12900129024");
        String requestNo = createRequest(owner, "求团：保温杯");
        String biz = loginAsOwnerOf("M0001", "12900129025");

        String first = quote(biz, requestNo, 6000L, 2, 7);
        String second = quote(biz, requestNo, 5500L, 2, 7);

        assertThat(second).isEqualTo(first);
        assertThat(quotes(owner, requestNo).size()).isEqualTo(1);
        // 二次报价等同改价，必须留痕
        assertThat(json.readTree(mvc().perform(get("/mp/group-request/" + requestNo + "/price-history"))
                .andReturn().getResponse().getContentAsString()).get("data")).hasSize(1);
    }

    @Test
    @DisplayName("商家能看到可报价的需求单池")
    void merchantSeesRequestPool() throws Exception {
        String owner = login("12900129026");
        createRequest(owner, "求团：拖把");
        String biz = loginAsOwnerOf("M0001", "12900129027");

        mvc().perform(get("/biz/group-request/pool").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("非商家不能报价")
    void normalUserCannotQuote() throws Exception {
        String owner = login("12900129028");
        String requestNo = createRequest(owner, "求团：雨伞");
        String normal = login("12900129029");

        mvc().perform(post("/biz/group-request/" + requestNo + "/quote")
                        .header("Authorization", "Bearer " + normal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPriceMinor\":1000,\"minQty\":1,\"validDays\":7}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- 商家团

    @Test
    @DisplayName("参团：人数累加；够起团人数即成团")
    void joinGroupBuyUntilFormed() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 4500L, 2);

        /*
         * 参团返回的是 `{ group, justReached, refundPerMember }` 而不是裸的团 ——
         * 端上要知道「这一脚是不是把团踢成了」，才能告诉他
         * **先参团的邻居也退了差价**（这个方案区别于其它拼团的地方）。
         * 后端原先直接返回团本身，于是端上 `res.group` 是 undefined，
         * 赋回去之后整个团详情页变成一片空白：点了参团、看到「参团成功」、然后什么都没了。
         */
        String a = login("12900129030");
        mvc().perform(post("/mp/group-buy/" + groupNo + "/join").header("Authorization", "Bearer " + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group.joinedCount").value(1))
                .andExpect(jsonPath("$.data.group.status").value("OPEN"))
                .andExpect(jsonPath("$.data.justReached").value(false));

        String b = login("12900129031");
        mvc().perform(post("/mp/group-buy/" + groupNo + "/join").header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.data.group.joinedCount").value(2))
                .andExpect(jsonPath("$.data.group.status").value("FORMED"))
                // 恰好踢成团的那一下才是 true，且要给出每人退多少
                .andExpect(jsonPath("$.data.justReached").value(true))
                .andExpect(jsonPath("$.data.refundPerMember").value(1000));
    }

    @Test
    @DisplayName("同一个人不能重复参团（否则「还差 N 人」会被一个人刷满）")
    void cannotJoinTwice() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 4500L, 5);
        String a = login("12900129032");

        mvc().perform(post("/mp/group-buy/" + groupNo + "/join").header("Authorization", "Bearer " + a));
        mvc().perform(post("/mp/group-buy/" + groupNo + "/join").header("Authorization", "Bearer " + a))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("团购列表与详情（游客可看 —— 分享出去的链接要能打开）")
    void groupBuyIsPublic() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 4500L, 3);

        mvc().perform(get("/mp/group-buy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc().perform(get("/mp/group-buy/" + groupNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupNo").value(groupNo))
                .andExpect(jsonPath("$.data.merchant.name").isNotEmpty());
    }

    @Test
    @DisplayName("★★★ 团卡片上的数字必须都是真的 —— 否则买家看到的是「¥NaN · 还差 人成团」")
    void groupCardCarriesEveryNumberThePageShows() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 3980L, 3);

        JsonNode g = json.readTree(mvc().perform(get("/mp/group-buy/" + groupNo))
                        .andExpect(jsonPath("$.code").value(0))
                        .andReturn().getResponse().getContentAsString()).get("data");

        /*
         * 这些字段少一个，页面上就是一个 NaN —— 而 NaN 不报错，它只是让人看不懂。
         * 实测过的形态：「¥NaN.NaN」「还差 [空] 人成团」「NaN:NaN:NaN」，
         * 三个页面（B 端团列表、C 端团列表、C 端团详情）上一个正确数字都没有 ——
         * 团开了等于没开，而接口一路 200。
         */
        assertThat(g.get("groupPrice").asLong()).isEqualTo(3980L);
        assertThat(g.get("basePrice").asLong()).isGreaterThan(3980L);
        assertThat(g.get("expireAt").asLong()).isGreaterThan(0L);
        assertThat(g.get("need").asInt()).as("还差几人由后端算，端上不要自己减").isEqualTo(3);
        assertThat(g.get("reached").asBoolean()).isFalse();
        assertThat(g.get("members").isArray()).isTrue();
        assertThat(g.get("merchant").get("name").asString()).isNotEmpty();
        // status 不能省成 reached：被平台中止的团人数可能已经够了
        assertThat(g.get("status").asString()).isEqualTo("OPEN");
    }

    // ---------------------------------------------------------------- helpers

    @Autowired
    private ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupBuyMapper groupBuyMapper;

    private String openGroupBuy(String merchantNo, String goodsNo, long price, int minCount) {
        var g = new ai.neargo.shop.marketing.group.entity.MktGroupBuy();
        g.setGroupNo("GB-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        g.setGoodsNo(goodsNo);
        g.setEntityNo(merchantNo);
        g.setTitle("团购商品");
        g.setCover("");
        g.setGroupPriceMinor(price);
        g.setOriginPriceMinor(price + 1000);
        g.setMinCount(minCount);
        g.setJoinedCount(0);
        g.setStatus("OPEN");
        g.setEndAt(System.currentTimeMillis() + 86_400_000L);
        groupBuyMapper.insert(g);
        return g.getGroupNo();
    }

    private void setBreachCount(String merchantNo, int count) {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setBreachCount(count);
        merchantMapper.updateById(m);
    }

    private String createRequest(String token, String title) throws Exception {
        String body = mvc().perform(post("/mp/group-request").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"expectCount\":5,\"days\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("requestNo").asString();
    }

    private String quote(String bizToken, String requestNo, long price, int minQty, int days) throws Exception {
        String body = mvc().perform(post("/biz/group-request/" + requestNo + "/quote")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPriceMinor\":" + price + ",\"minQty\":" + minQty
                                + ",\"validDays\":" + days + ",\"note\":\"含运费\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("quoteNo").asString();
    }

    private void revise(String bizToken, String quoteNo, long newPrice) throws Exception {
        mvc().perform(post("/biz/quote/" + quoteNo + "/revise")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPriceMinor\":" + newPrice + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void choose(String ownerToken, String requestNo, String quoteNo) throws Exception {
        mvc().perform(post("/mp/group-request/" + requestNo + "/choose")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteNo\":\"" + quoteNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private JsonNode requestDetail(String token, String requestNo) throws Exception {
        String body = mvc().perform(get("/mp/group-request/" + requestNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode quotes(String token, String requestNo) throws Exception {
        String body = mvc().perform(get("/mp/group-request/" + requestNo + "/quotes")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(json.readTree(body).get("data").get("userNo").asString());
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), json.readTree(body).get("data").get("userNo").asString());
        merchantMapper.updateById(m);
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
    /** 授予 B 端身份：写一条 owner 成员行（幂等）。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;


    // ---------------------------------------------------------------- 平台报价治理（P-8.2）

    @Test
    @DisplayName("★ 平台判毁约：报价置 BREACH，且毁约次数公示到报价卡上")
    void breachMarksQuoteAndMerchantCredit() throws Exception {
        String owner = login("13700137101");
        String requestNo = createRequest(owner, "毁约测试");
        String biz = loginAsOwnerOf("M0001", "13700137102");
        String quoteNo = quote(biz, requestNo, 15000L, 3, 7);

        int before = quoteCard(requestNo, quoteNo).get("merchant").get("breachCount").asInt();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/quotes/" + quoteNo + "/breach")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"接单后拒不发货，聊天记录见工单 TK1\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("BREACH"));

        // 毁约的报价从用户的报价列表里消失 —— 它按 status=ACTIVE 取，
        // 一条已判毁约的报价不该还挂在那里等人选
        assertThat(quoteNos(requestNo)).doesNotContain(quoteNo);

        /*
         * 毁约次数公示在**该商家后续的报价卡**上（ADR-003）。
         * 判定必须真的影响到用户看得见的东西，否则这个功能只是改了一个没人看的状态字段。
         */
        String requestNo2 = createRequest(owner, "毁约后的下一单");
        String quoteNo2 = quote(biz, requestNo2, 16000L, 3, 7);
        assertThat(quoteCard(requestNo2, quoteNo2).get("merchant").get("breachCount").asInt())
                .as("判毁约必须计入 breach_count，并出现在这家店后面的报价上")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("重复判毁约不叠加违规记录（幂等）")
    void breachIsIdempotent() throws Exception {
        String owner = login("13700137103");
        String requestNo = createRequest(owner, "幂等毁约");
        String biz = loginAsOwnerOf("M0002", "13700137104");
        String quoteNo = quote(biz, requestNo, 12000L, 2, 7);
        String bd = opsLogin("bd", "bd123");

        for (int i = 0; i < 2; i++) {
            mvc().perform(post("/ops/quotes/" + quoteNo + "/breach")
                            .header("Authorization", "Bearer " + bd)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"detail\":\"重复判定\"}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        String requestNo2 = createRequest(owner, "幂等毁约后的下一单");
        String quoteNo2 = quote(biz, requestNo2, 12500L, 2, 7);
        assertThat(quoteCard(requestNo2, quoteNo2).get("merchant").get("breachCount").asInt())
                .as("判两次只该算一次 —— 否则运营手抖点两下，商家白背一次违规").isEqualTo(1);
    }

    @Test
    @DisplayName("毁约理由必填：没有事实的处置在申诉时站不住")
    void breachNeedsDetail() throws Exception {
        String owner = login("13700137105");
        String requestNo = createRequest(owner, "空理由");
        String biz = loginAsOwnerOf("M0001", "13700137106");
        String quoteNo = quote(biz, requestNo, 9900L, 1, 7);
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/quotes/" + quoteNo + "/breach")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"   \"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("平台改价与商家改价进同一份价格历史")
    void opsPriceGoesToSameHistory() throws Exception {
        String owner = login("13700137107");
        String requestNo = createRequest(owner, "平台改价");
        String biz = loginAsOwnerOf("M0001", "13700137108");
        String quoteNo = quote(biz, requestNo, 10000L, 1, 7);
        revise(biz, quoteNo, 11000L);           // 商家自己改一次

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/quotes/" + quoteNo + "/price")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPriceMinor\":10500,\"reason\":\"商家把 105 打成 1050\"}"))
                .andExpect(jsonPath("$.code").value(0));

        // 用户看到的是同一份历史：平台改的那一笔不该长得不一样，也不该看不见
        assertThat(json.readTree(mvc().perform(get("/mp/group-request/" + requestNo + "/price-history"))
                .andReturn().getResponse().getContentAsString()).get("data").size())
                .as("商家改一次 + 平台改一次 = 两条历史").isEqualTo(2);
    }

    @Test
    @DisplayName("没有 quote:govern 的角色判不了毁约（客服不是招商）")
    void supportCannotMarkBreach() throws Exception {
        String owner = login("13700137109");
        String requestNo = createRequest(owner, "越权毁约");
        String biz = loginAsOwnerOf("M0001", "13700137110");
        String quoteNo = quote(biz, requestNo, 8800L, 1, 7);

        mvc().perform(post("/ops/quotes/" + quoteNo + "/breach")
                        .header("Authorization", "Bearer " + opsLogin("support", "support123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"我不该能判\"}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    /** C 端报价列表里的单号（只含 ACTIVE） */
    private java.util.List<String> quoteNos(String requestNo) throws Exception {
        String body = mvc().perform(get("/mp/group-request/" + requestNo + "/quotes"))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> out = new java.util.ArrayList<>();
        json.readTree(body).get("data").forEach(n -> out.add(n.get("quoteNo").asString()));
        return out;
    }

    /** 报价卡：C 端看到的那份（含公示的毁约次数） */
    private tools.jackson.databind.JsonNode quoteCard(String requestNo, String quoteNo)
            throws Exception {
        String body = mvc().perform(get("/mp/group-request/" + requestNo + "/quotes"))
                .andReturn().getResponse().getContentAsString();
        for (var n : json.readTree(body).get("data")) {
            if (quoteNo.equals(n.get("quoteNo").asString())) {
                return n;
            }
        }
        throw new AssertionError("报价卡上找不到 " + quoteNo);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }


    // ---------------------------------------------------------------- 平台拼团治理（P-8.1）

    @Test
    @DisplayName("★ 平台中止违规团：团置 FAILED，且从 C 端在售列表消失")
    void opsAbortsGroup() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 4500L, 3);
        String a = login("12900129090");
        mvc().perform(post("/mp/group-buy/" + groupNo + "/join").header("Authorization", "Bearer " + a));

        String goods = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/groups/" + groupNo + "/abort")
                        .header("Authorization", "Bearer " + goods)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"团购价高于原价，涉嫌虚假优惠\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        // 中止必须影响用户看得见的东西 —— 只改一个没人看的状态字段不算干预
        String list = mvc().perform(get("/mp/group-buy")).andReturn().getResponse().getContentAsString();
        assertThat(list).as("中止后的团不该还挂在 C 端等人参加").doesNotContain(groupNo);
    }

    @Test
    @DisplayName("已成团的不许中止：那一刻用户已付钱、商家已备货，要退得走售后")
    void formedGroupCannotBeAborted() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 4500L, 2);
        mvc().perform(post("/mp/group-buy/" + groupNo + "/join")
                .header("Authorization", "Bearer " + login("12900129091")));
        mvc().perform(post("/mp/group-buy/" + groupNo + "/join")
                        .header("Authorization", "Bearer " + login("12900129092")))
                .andExpect(jsonPath("$.data.group.status").value("FORMED"));

        mvc().perform(post("/ops/groups/" + groupNo + "/abort")
                        .header("Authorization", "Bearer " + opsLogin("goods", "goods123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"想中止已成团的\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("中止理由必填；无 marketing:govern 的角色中止不了")
    void abortNeedsReasonAndPermission() throws Exception {
        String groupNo = openGroupBuy("M0001", "G0001", 4500L, 3);
        String goods = opsLogin("goods", "goods123");

        mvc().perform(post("/ops/groups/" + groupNo + "/abort")
                        .header("Authorization", "Bearer " + goods)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));

        // BD 管招商不管商品价格，中止团不是他的活
        mvc().perform(post("/ops/groups/" + groupNo + "/abort")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"我不该能中止\"}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

}
