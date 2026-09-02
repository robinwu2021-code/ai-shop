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
 * 店铺码档案与印刷量登记（TDD-门店获客埋点与看板 §五 闸门 V5）。
 *
 * <p>这一页最容易出的问题是**把「还没人登记」显示成「印了 0 张」** ——
 * 两者在界面上长得一样，而运营据此判断该去催谁登记。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreQrcodeFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.service.StoreCodeService storeCodeService;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** <b>V5</b>：没登记过印刷量 → {@code printed} 是 null，不是 0。 */
    @Test
    @DisplayName("★ 没登记过印刷量的店 printed 给 null —— 与「印了 0 张」不是一回事")
    void unregisteredPrintedIsNullNotZero() throws Exception {
        String merchantNo = approvedMerchantNo("12600130001", "店铺码测试店A", "CM-QR-A");
        storeCodeService.ensureFor(merchantNo);

        String admin = opsLogin("admin", "admin123");
        JsonNode row = qrcodeRowOf(admin, merchantNo);
        assertThat(row).as("生成过码的店没有出现在店铺码列表里").isNotNull();
        // ★ 关键：null 而不是 0
        assertThat(row.get("printed").isNull())
                .as("从没登记过印刷量却显示成 0 —— 运营会以为已经登记过、印了零张")
                .isTrue();
        // 扫码次数相反：埋点一直在记，0 就是真的没人扫
        assertThat(row.get("scanCount").asLong()).isEqualTo(0);
    }

    @Test
    @DisplayName("★ 登记两次印刷 → printed 累加；冲减补负数行而不是改历史")
    void printedAccumulatesAndSupportsNegative() throws Exception {
        String merchantNo = approvedMerchantNo("12600130002", "店铺码测试店B", "CM-QR-B");
        storeCodeService.ensureFor(merchantNo);
        String admin = opsLogin("admin", "admin123");

        recordPrint(admin, merchantNo, 200, "10x10cm");
        recordPrint(admin, merchantNo, 300, "6x6cm");
        assertThat(qrcodeRowOf(admin, merchantNo).get("printed").asInt()).isEqualTo(500);
        // 尺寸取最近一次那批 —— 尺寸属于那次印刷，不是门店的固有属性
        assertThat(qrcodeRowOf(admin, merchantNo).get("size").asString()).isEqualTo("6x6cm");

        // 印多了冲减：补一行负数，历史行不动
        recordPrint(admin, merchantNo, -100, null);
        assertThat(qrcodeRowOf(admin, merchantNo).get("printed").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("登记 0 张被拒 —— 它既不是印了也不是冲减，留一行只是噪声")
    void zeroQtyRejected() throws Exception {
        String merchantNo = approvedMerchantNo("12600130003", "店铺码测试店C", "CM-QR-C");
        String admin = opsLogin("admin", "admin123");
        mvc().perform(post("/ops/stores/" + merchantNo + "/qrcode/print")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":0}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 扫过码之后 scanCount 是真的（埋点与店铺码页读的是同一份数据）")
    void scanCountComesFromRealVisits() throws Exception {
        String merchantNo = approvedMerchantNo("12600130004", "店铺码测试店D", "CM-QR-D");
        String code = storeCodeService.ensureFor(merchantNo);

        mvc().perform(get("/mp/store/by-code").param("storeCode", code)
                .param("deviceId", "DEV-QR-D")).andExpect(status().isOk());
        mvc().perform(get("/mp/store/by-code").param("storeCode", code)
                .param("deviceId", "DEV-QR-D")).andExpect(status().isOk());

        String admin = opsLogin("admin", "admin123");
        assertThat(qrcodeRowOf(admin, merchantNo).get("scanCount").asLong())
                .as("店铺码页的扫码数与埋点对不上 —— 两处读的不是同一份数据")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★ 店铺码要 store:page:audit：客服（无）被拦")
    void qrcodesArePermGated() throws Exception {
        String support = opsLogin("support", "support123");
        mvc().perform(get("/ops/stores/qrcodes").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));
    }

    /**
     * <b>V298 一店一码：两家分店各是各的码，扫谁算谁。</b>
     *
     * <p>此前一主体一码，两家分店贴的是同一张纸 —— 扫码数在分店之间分不开，
     * 而「哪家店的贴纸有用」正是商家问的第一个问题。
     *
     * <p>可证伪：把 {@code ensureForStore} 改回按主体发码，两家店会拿到同一个码，
     * 第一个断言立刻变红。
     */
    @Test
    @DisplayName("★ 两家分店发出来的是两个码，扫哪家算哪家")
    void eachStoreGetsItsOwnCode() throws Exception {
        String merchantNo = approvedMerchantNo("12600130005", "一店一码测试店", "CM-QR-E");
        String branch = extraStore(merchantNo, "南门店");

        String defaultCode = storeCodeService.ensureForStore(merchantNo, null);
        String branchCode = storeCodeService.ensureForStore(merchantNo, branch);
        assertThat(branchCode).as("两家分店拿到同一个码 —— 获客数据永远分不开").isNotEqualTo(defaultCode);

        // 扫分店的码：解析要能说出是哪家店
        var target = storeCodeService.resolveTarget(branchCode);
        assertThat(target.entityNo()).isEqualTo(merchantNo);
        assertThat(target.storeNo()).as("码解析不出门店 —— 埋点的 store_no 又会是空的").isEqualTo(branch);

        // 只扫分店，主店一次都不扫
        mvc().perform(get("/mp/store/by-code").param("storeCode", branchCode)
                .param("deviceId", "DEV-QR-E")).andExpect(status().isOk());

        String admin = opsLogin("admin", "admin123");
        assertThat(storeRowOf(admin, branch).get("scanCount").asLong())
                .as("扫的是分店的码，分店却没记上").isEqualTo(1);
        assertThat(storeRowOf(admin, defaultStoreNoOf(merchantNo)).get("scanCount").asLong())
                .as("★ 只扫了分店，主店也涨了 —— 等于没分开").isEqualTo(0);
    }

    /**
     * <b>发码幂等，换码要理由。</b>
     *
     * <p>换码会让已经贴在店里的物料全部变成死链。这一步的代价在线下，
     * 而线上只是一次点击 —— 不挡的话代价与操作难度完全不匹配。
     */
    @Test
    @DisplayName("★ 运营发码幂等；换码没给理由被拒，给了才换且新旧码不同")
    void opsIssueIsIdempotentAndReissueNeedsReason() throws Exception {
        String merchantNo = approvedMerchantNo("12600130006", "发码测试店", "CM-QR-F");
        String admin = opsLogin("admin", "admin123");

        String first = issue(admin, merchantNo);
        String again = issue(admin, merchantNo);
        assertThat(again).as("重复点「发码」把码换掉了 —— 上一批贴纸当场作废").isEqualTo(first);

        // 没给理由：拒
        mvc().perform(post("/ops/stores/" + merchantNo + "/qrcode/reissue")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        assertThat(issue(admin, merchantNo)).as("被拒的换码却把码改了").isEqualTo(first);

        // 给了理由：换
        String body = mvc().perform(post("/ops/stores/" + merchantNo + "/qrcode/reissue")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"贴纸印错了地址，整批重做\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String reissued = json.readTree(body).get("data").get("storeCode").asString();
        assertThat(reissued).as("给了理由却没真的换码").isNotEqualTo(first);
    }

    /** <b>没发过码的门店要出现在列表里</b> —— 看不见就没人去发。 */
    @Test
    @DisplayName("★ codeless=true 只列还没发码的店，发完就从这张单子上消失")
    void codelessListsStoresNeedingACode() throws Exception {
        String merchantNo = approvedMerchantNo("12600130007", "待发码测试店", "CM-QR-G");
        String branch = extraStore(merchantNo, "还没发码的分店");
        String admin = opsLogin("admin", "admin123");

        assertThat(codelessHas(admin, branch)).as("没有码的分店不在待办清单上 —— 运营看不见就不会去发").isTrue();
        issueForStore(admin, merchantNo, branch);
        assertThat(codelessHas(admin, branch)).as("发完码还赖在待办清单上").isFalse();
    }

    private String issue(String opsToken, String merchantNo) throws Exception {
        return issueForStore(opsToken, merchantNo, null);
    }

    private String issueForStore(String opsToken, String merchantNo, String storeNo) throws Exception {
        var req = post("/ops/stores/" + merchantNo + "/qrcode/issue")
                .header("Authorization", "Bearer " + opsToken);
        if (storeNo != null) {
            req = req.param("storeNo", storeNo);
        }
        String body = mvc().perform(req).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeCode").asString();
    }

    private boolean codelessHas(String opsToken, String storeNo) throws Exception {
        String body = mvc().perform(get("/ops/stores/qrcodes")
                        .header("Authorization", "Bearer " + opsToken)
                        .param("codeless", "true").param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (storeNo.equals(r.get("storeNo").asString())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode storeRowOf(String opsToken, String storeNo) throws Exception {
        String body = mvc().perform(get("/ops/stores/qrcodes")
                        .header("Authorization", "Bearer " + opsToken)
                        .param("keyword", storeNo).param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (storeNo.equals(r.get("storeNo").asString())) {
                return r;
            }
        }
        throw new AssertionError("门店 " + storeNo + " 不在店铺码列表里");
    }

    private String defaultStoreNoOf(String merchantNo) {
        return storeMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getIsDefault, 1)
                        .last("limit 1"))
                .getStoreNo();
    }

    /** 加一家非默认分店。门店号自带前缀，免得与共享种子里的号撞上。 */
    private String extraStore(String merchantNo, String name) {
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("ST-QR-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        st.setEntityNo(merchantNo);
        st.setName(name);
        st.setIsDefault(false);
        storeMapper.insert(st);
        return st.getStoreNo();
    }

    // ---------------------------------------------------------------- helpers

    private void recordPrint(String opsToken, String merchantNo, int qty, String size) throws Exception {
        String body = size == null
                ? "{\"qty\":" + qty + "}"
                : "{\"qty\":" + qty + ",\"size\":\"" + size + "\"}";
        mvc().perform(post("/ops/stores/" + merchantNo + "/qrcode/print")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
    }

    private JsonNode qrcodeRowOf(String opsToken, String merchantNo) throws Exception {
        String body = mvc().perform(get("/ops/stores/qrcodes")
                        .header("Authorization", "Bearer " + opsToken)
                        .param("keyword", merchantNo).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (merchantNo.equals(r.get("merchantNo").asString())) {
                return r;
            }
        }
        return null;
    }

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
