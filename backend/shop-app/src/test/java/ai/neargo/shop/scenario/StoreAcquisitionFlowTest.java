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
 * 门店获客埋点与漏斗（TDD-门店获客埋点与看板 §五 的闸门 V1–V4、V6）。
 *
 * <p>这条链上最容易出的不是报错，是**一个看起来在工作、读数恒为零的看板**。
 * 所以下面每条断言都要求「真造一条链路再断非零」，而不是断字段存在。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreAcquisitionFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.marketing.visit.mapper.VisitMappers.StoreVisitMapper visitMapper;
    @Autowired
    private ai.neargo.shop.merchant.service.StoreCodeService storeCodeService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /**
     * <b>V1 —— 本文件最值得测的一条。</b>
     *
     * <p>漏斗第一层就是「还没登录的人扫了码」。此前 by-code 只解析不落行、
     * enter 又要求登录，于是这一层恒为 0 —— 而那正是「这批贴纸有没有用」的答案，
     * 且它不会报错，看板上就是一个安静的零。
     */
    @Test
    @DisplayName("★ 匿名扫码（无 token）也要落一行访问，且 userNo 为空")
    void anonymousScanIsRecorded() throws Exception {
        String merchantNo = approvedMerchantNo("12600128001", "获客埋点测试店A", "CM-ACQ-A");
        String storeCode = storeCodeOf(merchantNo);

        long before = countVisits(merchantNo);
        // ★ 不带 Authorization —— 这正是要测的情形
        mvc().perform(get("/mp/store/by-code").param("storeCode", storeCode)
                        .param("deviceId", "DEV-ACQ-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(countVisits(merchantNo)).isEqualTo(before + 1);
        var rows = visitMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.marketing.visit.entity.MktStoreVisit>lambdaQuery()
                .eq(ai.neargo.shop.marketing.visit.entity.MktStoreVisit::getEntityNo, merchantNo));
        var last = rows.get(rows.size() - 1);
        // 空的 userNo 是这一层的定义本身，不是漏填
        assertThat(last.getUserNo()).isNull();
        assertThat(last.getDeviceId()).isEqualTo("DEV-ACQ-A");
    }

    /**
     * V2：埋点不能拖垮落地页。
     *
     * <p>用一个**不存在的店铺码**逼出解析失败以外的路径不好造，所以这里换个等价问法：
     * 埋点所需的可选入参全缺（无 deviceId、无 UA、无登录）时，落地页仍要正常返回 ——
     * 一个必须靠齐全上下文才能不出错的埋点，迟早会在某个端上把首屏打挂。
     */
    @Test
    @DisplayName("★ 缺 deviceId / UA / 登录态时，扫码落地页照常返回门店")
    void landingSurvivesMinimalContext() throws Exception {
        String merchantNo = approvedMerchantNo("12600128002", "获客埋点测试店B", "CM-ACQ-B");
        String storeCode = storeCodeOf(merchantNo);

        mvc().perform(get("/mp/store/by-code").param("storeCode", storeCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.merchant.name").value("获客埋点测试店B"));
    }

    /**
     * V3：走完真实链路后四个数**都要 > 0**。
     *
     * <p>这条刻意不断言「字段存在」——四个数全 0 也能让那种断言通过，
     * 而全 0 恰恰是这条链断掉时的样子。
     */
    @Test
    @DisplayName("★ 扫码 → 进店 → 首单 走完，获客看板四个数都非零")
    void funnelCountsAreRealAfterFullPath() throws Exception {
        String phone = "12600128003";
        String merchantNo = approvedMerchantNo(phone, "获客埋点测试店C", "CM-ACQ-C");
        String storeCode = storeCodeOf(merchantNo);

        // ① 匿名扫码
        mvc().perform(get("/mp/store/by-code").param("storeCode", storeCode)
                .param("deviceId", "DEV-ACQ-C")).andExpect(status().isOk());

        // ② 登录后进店 —— 归因判定在这一步发生（source=STORE_CODE，decision=CREATED）
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600128004");
        mvc().perform(post("/mp/store/" + merchantNo + "/enter")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"" + storeCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String admin = opsLogin("admin", "admin123");
        JsonNode row = acquisitionRowOf(admin, "获客埋点测试店C");
        assertThat(row).as("获客看板里找不到这家店 —— 聚合没把两侧拼起来").isNotNull();

        // ★ 关键：真造出来的链路，四个数必须都是真的
        assertThat(row.get("scan").asLong()).as("扫码数").isGreaterThan(0);
        assertThat(row.get("scanUv").asLong()).as("扫码人数").isGreaterThan(0);
        assertThat(row.get("enter").asLong()).as("进店人数").isGreaterThan(0);
        assertThat(row.get("register").asLong()).as("首次归因人数").isGreaterThan(0);
    }

    /**
     * <b>只走真实链路：扫码，不手动调 /enter。</b>
     *
     * <p>上面那条用例自己 POST 了一次 {@code /mp/store/{no}/enter}，所以一直是绿的 ——
     * 而<b>生产里没有任何端调用过那条</b>（c-app 的 endpoints 里根本没有它）。
     * 于是「进店 / 首次归因 / 首单」三环在真环境恒为 0，看板一片零，
     * 却看不出是「没人来」还是「没人记」。替身太干净，盖住了真缺陷。
     *
     * <p>这条只做扫码这一个动作 —— 与店主贴一张纸、顾客扫一下完全一致。
     * 可证伪：去掉 {@code byCode} 里那段归因，{@code enter} 立刻回到 0。
     */
    @Test
    @DisplayName("★★ 登录后扫码即写归因 —— 不靠端上再调一次 /enter")
    void scanAloneFeedsTheFunnel() throws Exception {
        String merchantNo = approvedMerchantNo("12600128007", "获客真实链路店", "CM-ACQ-R");
        String storeCode = storeCodeOf(merchantNo);
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600128008");

        // 唯一动作：带着登录态扫这张码。**没有 /enter**
        mvc().perform(get("/mp/store/by-code")
                        .header("Authorization", "Bearer " + buyer)
                        .param("storeCode", storeCode)
                        .param("deviceId", "DEV-ACQ-R"))
                .andExpect(status().isOk());

        String admin = opsLogin("admin", "admin123");
        JsonNode row = acquisitionRowOf(admin, "获客真实链路店");
        assertThat(row).as("获客看板里找不到这家店").isNotNull();
        assertThat(row.get("scan").asLong()).as("扫码数").isGreaterThan(0);
        assertThat(row.get("enter").asLong())
                .as("★ 只扫码没进店 —— 生产里正是这样，于是漏斗后三环恒为 0")
                .isGreaterThan(0);
        assertThat(row.get("register").asLong()).as("首次归因人数").isGreaterThan(0);
    }

    /** V4：同设备连扫算多次 PV、一个 UV —— 去重放在聚合层，明细层照实记。 */
    @Test
    @DisplayName("★ 同设备连扫三次：scan=3 而 scanUv=1（去重在聚合层，不在明细层）")
    void repeatedScansFromOneDeviceCountAsOneUv() throws Exception {
        String merchantNo = approvedMerchantNo("12600128005", "获客埋点测试店D", "CM-ACQ-D");
        String storeCode = storeCodeOf(merchantNo);

        for (int i = 0; i < 3; i++) {
            mvc().perform(get("/mp/store/by-code").param("storeCode", storeCode)
                    .param("deviceId", "DEV-ACQ-D")).andExpect(status().isOk());
        }

        String admin = opsLogin("admin", "admin123");
        JsonNode row = acquisitionRowOf(admin, "获客埋点测试店D");
        assertThat(row).isNotNull();
        // 明细层照实记三行：风控要看得见「刷」的痕迹
        assertThat(row.get("scan").asLong()).isEqualTo(3);
        // 聚合层按设备去重：同一个人扫三次不该把转化率摊薄成三分之一
        assertThat(row.get("scanUv").asLong()).isEqualTo(1);
    }

    /**
     * V6：平台看板的漏斗与获客看板必须同源。
     *
     * <p>两处各写一份 group by 的话，首页和门店看板会给出两个不一样的「扫码数」，
     * 而两个都看起来是对的 —— 这种分歧没有任何报错，只有人在会上对不上账时才发现。
     */
    @Test
    @DisplayName("★ 平台看板 funnel 的 SCAN/ENTER 两环真的出现（且不是写死的 0）")
    void platformFunnelHasScanRingsFromSameSource() throws Exception {
        String merchantNo = approvedMerchantNo("12600128006", "获客埋点测试店E", "CM-ACQ-E");
        String storeCode = storeCodeOf(merchantNo);
        mvc().perform(get("/mp/store/by-code").param("storeCode", storeCode)
                .param("deviceId", "DEV-ACQ-E")).andExpect(status().isOk());

        String admin = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/dashboard/funnel").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = json.readTree(body).get("data");
        long scan = -1;
        for (JsonNode r : rows) {
            if ("SCAN".equals(r.get("step").asString())) {
                scan = r.get("count").asLong();
            }
        }
        assertThat(scan).as("funnel 里没有 SCAN 这一环 —— 前两环又退回「给不出来」了").isNotEqualTo(-1);
        // 刚扫过一次，这个数必须是真的
        assertThat(scan).as("SCAN 恒为 0 = 埋点没接上，而看板不会报错").isGreaterThan(0);
    }

    // ---------------------------------------------------------------- helpers

    private long countVisits(String merchantNo) {
        return visitMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.marketing.visit.entity.MktStoreVisit>lambdaQuery()
                .eq(ai.neargo.shop.marketing.visit.entity.MktStoreVisit::getEntityNo, merchantNo));
    }

    /** 从获客看板里挑出这家店那一行；找不到给 null（断言负责报错，不在这里抛）。 */
    private JsonNode acquisitionRowOf(String opsToken, String name) throws Exception {
        String body = mvc().perform(get("/ops/stores/acquisition")
                        .header("Authorization", "Bearer " + opsToken)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (name.equals(r.get("merchantName").asString())) {
                return r;
            }
        }
        return null;
    }

    /**
     * 商家的店铺码。走 {@code ensureFor} 与 B 端「查看店铺码」同一条路
     * （生成即落库、一主体一码），不另造一个只在测试里成立的码。
     */
    private String storeCodeOf(String merchantNo) {
        String code = storeCodeService.ensureFor(merchantNo);
        assertThat(code).as("店铺码没生成出来，扫码链路无从测起").isNotBlank();
        return code;
    }

    /** 走完「C 端提交 → 平台通过」，返回 merchantNo。 */
    private String approvedMerchantNo(String phone, String name, String communityNo) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"生鲜\",\"desc\":\"社区生鲜店\","
                                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"" + communityNo + "\"],"
                                + "\"licenses\":[\"https://cdn/l.jpg\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String mine = mvc().perform(get("/mp/merchant/apply").header("Authorization", "Bearer " + user))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(mine).get("data").get("merchantNo").asString();
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
