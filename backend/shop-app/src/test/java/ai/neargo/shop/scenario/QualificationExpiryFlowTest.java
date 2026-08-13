package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.merchant.service.MerchantGovernService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 资质到期（落地清单 P1-7）。
 *
 * <p>修的是一个「系统永远不知道」的缺口：资质此前只以图片 URL 留在入驻申请单上，
 * 主体上没有资质记录、没有有效期；而上架校验读的是审核时写死的 {@code category_codes}
 * ——证过期了那串编码不会变，商家照样上架、系统照样放行。
 *
 * <p>两道防线，这里都要验到：定时扫描覆盖<b>已经在架的</b>，
 * 上架校验覆盖<b>正要上架的</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("资质到期：系统要知道谁的证过期了")
class QualificationExpiryFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MerchantGovernService governService;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 扫描把过期资质置为 EXPIRED，且商家被标记为「有过期资质」")
    void scanMarksExpired() throws Exception {
        String ops = opsLogin();
        saveQual(ops, "M0001", "食品经营许可证", System.currentTimeMillis() - 86_400_000L)
                .andExpect(jsonPath("$.code").value(0));

        assertThat(governService.hasExpiredQualification("M0001"))
                .as("登记时就已过期的，保存那一刻就该是 EXPIRED —— 不必等扫描")
                .isTrue();

        // 扫描是给「登记时还没过期、后来才过期」的那批用的
        assertThat(governService.expireOverdueQualifications()).isNotNull();
    }

    @Test
    @DisplayName("★ 长期有效（有效期留空）不能被误判为过期")
    void permanentQualificationNotExpired() throws Exception {
        String ops = opsLogin();
        saveQual(ops, "M0002", "营业执照（长期）", null)
                .andExpect(jsonPath("$.data.status").value("VALID"));

        governService.expireOverdueQualifications();

        assertThat(governService.hasExpiredQualification("M0002"))
                .as("把「没填有效期」当成「已过期」会把持长期执照的商家全部误伤下架")
                .isFalse();
    }

    @Test
    @DisplayName("续证后状态回到 VALID —— 否则商家会以为「传了也没用」")
    void renewClearsExpired() throws Exception {
        String ops = opsLogin();
        String body = saveQual(ops, "M0002", "食品经营许可证", System.currentTimeMillis() - 1000)
                .andReturn().getResponse().getContentAsString();
        String qualNo = json.readTree(body).get("data").get("qualNo").asString();
        assertThat(governService.hasExpiredQualification("M0002")).isTrue();

        // 商家续了证，运营更新有效期
        mvc().perform(post("/ops/merchants/M0002/qualifications")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qualNo\":\"" + qualNo + "\",\"qualType\":\"FOOD_PERMIT\","
                                + "\"qualName\":\"食品经营许可证\",\"expireAt\":"
                                + (System.currentTimeMillis() + 86_400_000L) + "}"))
                .andExpect(jsonPath("$.data.status").value("VALID"));

        assertThat(governService.hasExpiredQualification("M0002")).isFalse();
    }

    @Test
    @DisplayName("撤销不物理删 —— 「当初有没有这张证」要能查")
    void revokeKeepsRecord() throws Exception {
        String ops = opsLogin();
        String body = saveQual(ops, "M0002", "待撤销的证", null)
                .andReturn().getResponse().getContentAsString();
        String qualNo = json.readTree(body).get("data").get("qualNo").asString();

        mvc().perform(post("/ops/qualifications/" + qualNo + "/revoke")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        assertThat(governService.qualifications("M0002"))
                .anyMatch(q -> q.qualNo().equals(qualNo));
    }

    @Test
    @DisplayName("★ 上架那道防线：过期后 Port 报「有过期资质」，上架校验据此拦下")
    void listingGuardSeesExpiry() throws Exception {
        String ops = opsLogin();
        saveQual(ops, "M0001", "食品经营许可证", System.currentTimeMillis() - 1000);

        /*
         * 直接验 Port —— 它就是 MerchantGoodsServiceImpl.requireCategoryAuthorized
         * 调用的那个方法。铺整条上架链路（建商品、配类目、配 required_code）
         * 只会把这条用例变成一个「商品创建」的测试，而要验的是这一道判断本身。
         */
        assertThat(merchantQueryPort.hasExpiredQualification("M0001"))
                .as("上架校验靠它拦；返回 false 的话，证过期了商家照样能上新")
                .isTrue();

        // 没有任何资质记录的商家不该被拦 —— 存量商家都还没补录
        assertThat(merchantQueryPort.hasExpiredQualification("M9999"))
                .as("一律拦会把还没补录资质的存量商家全部挡死")
                .isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions saveQual(
            String ops, String merchantNo, String name, Long expireAt) throws Exception {
        String expire = expireAt == null ? "null" : String.valueOf(expireAt);
        return mvc().perform(post("/ops/merchants/" + merchantNo + "/qualifications")
                .header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qualType\":\"FOOD_PERMIT\",\"qualName\":\"" + name
                        + "\",\"expireAt\":" + expire + "}"));
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }
}
