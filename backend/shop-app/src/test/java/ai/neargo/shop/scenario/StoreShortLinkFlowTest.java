package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 店铺码短链 {@code GET /s/<码>}（TDD-店铺码与分享 §3.5）。
 *
 * <p><b>这条链路此前是断的，而断点在参数上</b>：链接一直在发、码一直解析得出来，
 * nginx 那一跳把路径里的码丢了（{@code return 302 /c/$is_args$args}）。
 * 所以这一组用例盯的不是「有没有跳」，是<b>跳过去的 URL 上那几个参数还在不在</b> ——
 * 少了 {@code storeCode} 就落不到店，少了 {@code from=QR} 则扫码来的单会按平台流量计费
 * （ADR-004 §6 的费率档）。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreShortLinkFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.service.StoreCodeService storeCodeService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 码落到那家店：Location 上必须同时有 storeCode 与 from=QR")
    void shortLinkCarriesCodeAndAttribution() throws Exception {
        String merchantNo = approvedMerchantNo("12600140001", "短链测试店A", "CM-SL-A");
        String code = storeCodeService.ensureFor(merchantNo);
        assertThat(code).as("发码本身要成功，否则下面测的是空字符串").isNotBlank();

        String location = locationOf(get("/s/" + code));

        assertThat(location).as("落点是门店页").startsWith("/c/#/pages/store/index");
        assertThat(location).as("**码要带过去** —— 丢了它就是今天线上那个缺陷")
                .contains("storeCode=" + code);
        assertThat(location).as("扫码归因：少了它订单按平台流量计费，商家费率档就错了")
                .contains("from=QR");
    }

    @Test
    @DisplayName("★★ 商品号原样透传 —— 老链接用的就是 ?g=")
    void goodsParamIsPassedThrough() throws Exception {
        String merchantNo = approvedMerchantNo("12600140002", "短链测试店B", "CM-SL-B");
        String code = storeCodeService.ensureFor(merchantNo);

        String location = locationOf(get("/s/" + code).param("g", "G0001"));

        assertThat(location).contains("storeCode=" + code).contains("g=G0001");
    }

    @Test
    @DisplayName("★★ 码不认识就落首页，不给 404 —— 那些码可能已经印在包装上了")
    void unknownCodeFallsBackToHomeNot404() throws Exception {
        MvcResult r = mvc().perform(get("/s/NOSUCHCODE9")).andReturn();

        assertThat(r.getResponse().getStatus()).as("不是 404，也不是 500").isEqualTo(302);
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/c/");
    }

    @Test
    @DisplayName("★★★ 码进不了 Location —— 开放重定向被两道防线各挡一段")
    void illegalCodeCharsNeverReachLocation() throws Exception {
        /*
         * 第一道在容器里：编码过的 ../ 连 Controller 都进不来，Tomcat 直接 400。
         * **这一条比我原先以为的更强** —— 原来的断言写的是「落首页」，实测是 400，
         * 而 400 意味着那段字符串从来没被我的代码碰过。
         */
        assertThat(mvc().perform(get("/s/{code}", "..%2F..%2Fevil.com")).andReturn().getResponse().getStatus())
                .as("路径逃逸在容器层就被拒").isEqualTo(400);

        /*
         * 第二道是 CODE_CHARS：`$` 是合法 URL 字符，能进到 PathVariable 里 ——
         * 挡它的只有白名单本身。去掉那个 matches 判断，这一条就会把 `$` 拼进 Location。
         */
        String location = locationOf(get("/s/{code}", "abc$evil"));
        assertThat(location).as("白名单之外的码按「不认识」处理，不回显、不拼接").isEqualTo("/c/");
        assertThat(location).doesNotContain("evil");
    }

    @Test
    @DisplayName("★★★ 302 不许被全局信封裹住 —— 响应体要是空的")
    void redirectIsNotWrappedInApiEnvelope() throws Exception {
        String merchantNo = approvedMerchantNo("12600140003", "短链测试店C", "CM-SL-C");
        String code = storeCodeService.ensureFor(merchantNo);

        MvcResult r = mvc().perform(get("/s/" + code)).andReturn();

        /*
         * ApiResponseWrapper 只放行 /internal/**，/s/** 不在白名单里。
         * 302 没有响应体，ResponseBodyAdvice 因此不触发 —— 但「看起来安全」正是
         * [[global-envelope-breaks-internal-contracts]] 那次的说法（200 + 合法 JSON + 字段全 null）。
         * 所以这一条把它钉住：有人改成返回 body 的写法时，这里要红。
         */
        assertThat(r.getResponse().getContentAsString()).as("裹了信封就会出现 {\"code\":0,…}").isEmpty();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
    }

    private String locationOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        MvcResult r = mvc().perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as("这一跳一律 302（不是 301：门店会改代码、也可能停业）")
                .isEqualTo(302);
        return r.getResponse().getHeader("Location");
    }

    /*
     * 下面两个帮手与 StoreQrcodeFlowTest 里的同名方法一致。
     * **没有共用**：那样要把它们提到 support 包，而那是另一笔改动 ——
     * 这一组用例自己建商家、不碰任何共享种子（[[restore-shared-seeds-in-tests]]），
     * 用的社区号与手机号都带 SL 前缀，与别的测试不重叠。
     */
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
