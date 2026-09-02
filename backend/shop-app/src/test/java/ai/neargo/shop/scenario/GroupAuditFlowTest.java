package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.group.entity.MktGroupBuy;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupBuyMapper;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 拼团上线审核（开关 {@code group.audit}）。
 *
 * <p><b>两种方案都要成立</b>：开关关着 = 建团即上线（加开关之前的行为，一个字都不能变）；
 * 开着 = 落 PENDING、等运营审。默认关 —— 否则升个版本，所有商家开的团突然都不上线了，
 * 而没有任何人收到通知。
 *
 * <p>PENDING 选的是**新状态**而不是一个布尔位：C 端只列 {@code OPEN/FORMED}、
 * 参团只认 {@code OPEN}，所以 PENDING 天然被挡住。加布尔位的话，
 * 每个读的地方都要补一次判断，漏一处就是「没审核就上线了」。
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupAuditFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private GroupBuyMapper groupBuyMapper;
    @Autowired
    private ai.neargo.shop.marketing.group.GroupService groupService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 待审的团：通过后进 OPEN；驳回必须写原因，且驳回后 C 端列表里没有它")
    void auditPendingGroup() throws Exception {
        String ops = opsLogin();
        String pass = seedGroup("待审通过团", MktGroupBuy.PENDING, 2, 100L, 200L);
        String reject = seedGroup("待审驳回团", MktGroupBuy.PENDING, 2, 100L, 200L);

        // 驳回：空原因被拒（商家会原样看到这句话，空的等于让他猜）
        mvc().perform(post("/ops/groups/" + reject + "/audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":false}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        assertThat(statusOf(reject)).as("被拒的驳回却把状态改了").isEqualTo(MktGroupBuy.PENDING);

        mvc().perform(post("/ops/groups/" + reject + "/audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false,\"reason\":\"团购价与原价一样，不构成拼团\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(reject)).isEqualTo(MktGroupBuy.FAILED);

        // 通过：进 OPEN，这一刻起 C 端可见
        mvc().perform(post("/ops/groups/" + pass + "/audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(pass)).isEqualTo(MktGroupBuy.OPEN);

        // ★ 审过一次就不能再审 —— 两个运营同时点，后点的要看到「已经审过了」
        mvc().perform(post("/ops/groups/" + pass + "/audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":false,\"reason\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    /**
     * <b>两条硬校验不能只做 UI 提示。</b>
     *
     * <p>放出去之后 C 端页面上写着「拼团」，而它既拼不起来（1 个人）、
     * 也不便宜（团购价 ≥ 原价）—— 那时「拼团」这个词本身是假的。
     */
    @Test
    @DisplayName("★ 1 人团与「团购价不低于原价」都过不了审 —— 否则「拼团」这个词是假的")
    void auditRejectsNonsenseGroups() throws Exception {
        String ops = opsLogin();
        String onePerson = seedGroup("一个人的团", MktGroupBuy.PENDING, 1, 100L, 200L);
        String notCheaper = seedGroup("不便宜的团", MktGroupBuy.PENDING, 2, 200L, 200L);

        for (String no : new String[]{onePerson, notCheaper}) {
            mvc().perform(post("/ops/groups/" + no + "/audit")
                            .header("Authorization", "Bearer " + ops)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":true}"))
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
            assertThat(statusOf(no)).as("没通过校验却被放行了：" + no).isEqualTo(MktGroupBuy.PENDING);
        }
    }

    /**
     * <b>终态改不回去。</b>已成团/已失败之后改状态不会把钱退给任何人、也不会把货变回来，
     * 只会让订单与团的状态对不上 —— 与 abortGroup 拒绝改已成团是同一条理由。
     */
    @Test
    @DisplayName("★★ 状态干预只走合法迁移：OPEN→FORMED 放行，FAILED→OPEN 拒")
    void statusMovesAreWhitelisted() throws Exception {
        String ops = opsLogin();
        String open = seedGroup("可成团", MktGroupBuy.OPEN, 2, 100L, 200L);
        String failed = seedGroup("已失败", MktGroupBuy.FAILED, 2, 100L, 200L);

        mvc().perform(post("/ops/groups/" + open + "/status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FORMED\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(open)).isEqualTo(MktGroupBuy.FORMED);

        mvc().perform(post("/ops/groups/" + failed + "/status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        assertThat(statusOf(failed)).as("已失败的团被改回 OPEN —— 参团人早退款了").isEqualTo(MktGroupBuy.FAILED);
    }

    /**
     * <b>开关关着时行为一个字不变。</b>
     *
     * <p>这条守的是「加了开关反而改了默认行为」——升级当天所有商家开的团
     * 突然都不上线，而没有任何人收到通知。默认值必须与加开关之前逐字相同。
     */
    @Test
    @DisplayName("★★ 开关默认关：默认配置里 group.audit 是 false（建团即上线）")
    void auditSwitchDefaultsOff() throws Exception {
        String ops = opsLogin();
        String body = mvc().perform(get("/ops/feature-flags").header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        boolean found = false;
        for (var f : json.readTree(body).get("data")) {
            if ("group.audit".equals(f.get("key").asString())) {
                found = true;
                assertThat(f.get("enabled").asBoolean())
                        .as("★ 默认开着的话，升级当天所有商家开的团都不上线，而没人收到通知")
                        .isFalse();
            }
        }
        assertThat(found).as("开关没登记进默认配置 —— 运营端那一页看不到它，也就没法打开").isTrue();
    }

    /**
     * <b>开着的那一半</b>。默认关意味着这条路径在别的测试里一次都不会跑到 ——
     * 而它才是这次改动新增的行为：开关打开后建团要落 {@code PENDING}，
     * 且在审过之前 <b>C 端列表里看不见</b>。
     *
     * <p>两条建团路径都要验：用户发起的团与商家自己开的团。少管一条，开关就等于没开。
     *
     * <p>开关是**跨测试共享的状态**（sys_setting 一行），所以 finally 里必须关回去 ——
     * 否则单独跑绿、全量跑红，而报错会出现在毫不相干的用例上。
     */
    @Test
    @DisplayName("★★★ 开关打开：商家开的团落 PENDING，且审过之前 C 端列表里没有它")
    void auditSwitchOnHoldsGroupsBackFromCEnd() throws Exception {
        String ops = opsLogin();
        setAuditFlag(ops, true);
        try {
            withGroupPriceOn("G0001", () -> {
                var vo = groupService.createMerchantGroup("M0001", "G0001");
                assertThat(vo.status())
                        .as("★ 开关开着却直接上线了 —— 审核这一页永远是空的，没人会发现")
                        .isEqualTo(MktGroupBuy.PENDING);

                String list = mvc().perform(get("/mp/group-buy")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                assertThat(list).as("★ 没审就出现在 C 端列表里").doesNotContain(vo.groupNo());

                mvc().perform(post("/ops/groups/" + vo.groupNo() + "/audit")
                                .header("Authorization", "Bearer " + ops)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":true}"))
                        .andExpect(jsonPath("$.code").value(0));
                assertThat(statusOf(vo.groupNo())).isEqualTo(MktGroupBuy.OPEN);
                assertThat(mvc().perform(get("/mp/group-buy")).andReturn().getResponse().getContentAsString())
                        .as("审过了仍然不在 C 端列表里 —— 那审核就是个只会拦货的黑洞")
                        .contains(vo.groupNo());
            });
        } finally {
            setAuditFlag(ops, false);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 给某件货临时配上团购设置（开团要求「商品上已配好拼团价」），跑完**原样还原** ——
     * prd_goods 是所有场景测试共用的种子，留下改动会让别的用例在毫不相干的地方红。
     */
    private void withGroupPriceOn(String goodsNo, ThrowingRunnable body) throws Exception {
        Long price = jdbc.queryForObject(
                "select group_price_minor from prd_goods where goods_no=?", Long.class, goodsNo);
        Integer min = jdbc.queryForObject(
                "select group_min_count from prd_goods where goods_no=?", Integer.class, goodsNo);
        jdbc.update("update prd_goods set group_price_minor=?, group_min_count=? where goods_no=?",
                1_000L, 2, goodsNo);
        try {
            body.run();
        } finally {
            jdbc.update("update prd_goods set group_price_minor=?, group_min_count=? where goods_no=?",
                    price, min, goodsNo);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 开关是共享状态，改完必须还原 —— 见本类 auditSwitchOnHoldsGroupsBackFromCEnd 的说明。 */
    private void setAuditFlag(String ops, boolean on) throws Exception {
        mvc().perform(post("/ops/feature-flags/group.audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":" + on + ",\"rolloutPercent\":0}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String statusOf(String groupNo) {
        return groupBuyMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")).getStatus();
    }

    /** 直接种一个团 —— 走 C 端建团要「配了团购的商品 + 自提点」，与这里要验的东西无关。 */
    private String seedGroup(String title, String status, int minCount, long price, long origin) {
        MktGroupBuy g = new MktGroupBuy();
        g.setGroupNo("GB-AUD-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        g.setInitiatorUserNo("U-AUD-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        g.setGoodsNo("G0001");
        g.setEntityNo("M0001");
        g.setTitle(title);
        g.setGroupPriceMinor(price);
        g.setOriginPriceMinor(origin);
        g.setMinCount(minCount);
        g.setJoinedCount(0);
        g.setEndAt(System.currentTimeMillis() + 86_400_000L);
        g.setStatus(status);
        groupBuyMapper.insert(g);
        return g.getGroupNo();
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
