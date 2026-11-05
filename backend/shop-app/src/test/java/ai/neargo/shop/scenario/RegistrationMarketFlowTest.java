package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.user.service.AuthService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.ThreadLocalRandom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TDD-注册地区与三步建店 AC1–AC4、AC6、AC9 的真实链路：走 {@code /biz/auth/login}、
 * {@code /biz/merchant/quick-start}、{@code /biz/stores/mine} 与 H2 的 {@code usr_account} / {@code mch_store}。
 *
 * <p>测试世界：部署默认 CN，可注册地区 {@code CN,TW}（见 application-test.yml）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RegistrationMarketFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @Autowired
    private MerchantAdminPort merchantAdminPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private static String probePhone() {
        return "1270" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    /** 登录并返回 token；marketCode 为 null 时请求体里不带这个字段（旧端的样子）。 */
    private String login(MockMvc mvc, String phone, String marketCode) throws Exception {
        otpStore.save(phone, "135790");
        String market = marketCode == null ? "" : ",\"marketCode\":\"" + marketCode + "\"";
        String body = mvc.perform(post("/biz/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"135790\",\"agreed\":true" + market + "}"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("data").path("token").asString();
        assertThat(token).as(body).startsWith("btk_");
        return token;
    }

    private String userNoOf(String phone) {
        return jdbc.queryForObject("select user_no from usr_identity where identity_type='PHONE' and identity_value=?",
                String.class, phone);
    }

    private String marketOf(String phone) {
        return jdbc.queryForObject("select market from usr_account where user_no=?", String.class, userNoOf(phone));
    }

    @Test
    @DisplayName("AC1 / AC2 新号带可注册地区建号即写入；已有账号再带别的地区登录不改")
    void marketIsFixedAtRegistrationOnly() throws Exception {
        var mvc = mvc();
        String phone = probePhone();
        login(mvc, phone, "TW");
        assertThat(marketOf(phone)).isEqualTo("TW");

        login(mvc, phone, "CN");
        assertThat(marketOf(phone)).as("已有账号的地区不随登录参数改").isEqualTo("TW");
        assertThat(authService.accountMarket(userNoOf(phone))).isEqualTo("TW");
    }

    @Test
    @DisplayName("AC3 不在可注册地区或不带地区：建号写部署默认")
    void unknownOrMissingMarketFallsBackToTheDeploymentDefault() throws Exception {
        var mvc = mvc();
        String unknown = probePhone();
        login(mvc, unknown, "AE");
        assertThat(marketOf(unknown)).isEqualTo("CN");

        String missing = probePhone();
        login(mvc, missing, null);
        assertThat(marketOf(missing)).isEqualTo("CN");
    }

    @Test
    @DisplayName("AC4 存量账号 market 为空：按部署默认")
    void legacyAccountWithoutMarketReadsTheDeploymentDefault() throws Exception {
        String phone = probePhone();
        login(mvc(), phone, "TW");
        jdbc.update("update usr_account set market = null where user_no=?", userNoOf(phone));
        assertThat(authService.accountMarket(userNoOf(phone))).isEqualTo("CN");
    }

    @Test
    @DisplayName("AC6 quick-start 传了与账号不同的市场：10400，且不建任何主体；不传则用账号市场")
    void quickStartRejectsAMarketOtherThanTheAccounts() throws Exception {
        var mvc = mvc();
        /*
         * 账号 TW、请求 CN：CN 在测试库里是启用的，所以拦住它的只能是「必须等于账号市场」这一条 ——
         * 反过来（账号 CN、请求 TW）会先被「TW 未启用」拦下，撤掉被测那一行也照样绿（已实测）。
         */
        String phone = probePhone();
        String token = login(mvc, phone, "TW");
        String body = mvc.perform(post("/biz/merchant/quick-start").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"换市场的店\",\"market\":\"CN\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("code").asInt()).as(body).isEqualTo(10400);
        assertThat(jdbc.queryForObject("select count(*) from mch_account where user_no=?", Integer.class,
                userNoOf(phone))).as("被拒之前不能先建壳").isZero();

        String cnPhone = probePhone();
        String cnToken = login(mvc, cnPhone, "CN");
        String ok = mvc.perform(post("/biz/merchant/quick-start").header("Authorization", "Bearer " + cnToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"不传市场的店\",\"addressDetail\":\" 3F-2 \"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(ok).path("code").asInt()).as(ok).isZero();
        assertThat(jdbc.queryForObject("select e.market from mch_entity e join mch_account a"
                + " on a.entity_no = e.entity_no where a.user_no=?", String.class, userNoOf(cnPhone)))
                .isEqualTo("CN");
        assertThat(jdbc.queryForObject(
                "select s.address_detail from mch_store s join mch_account a on a.entity_no = s.entity_no"
                        + " where a.user_no=?", String.class, userNoOf(cnPhone)))
                .as("AC9 楼层门牌随建店落库").isEqualTo("3F-2");
    }

    @Test
    @DisplayName("AC9 自填行业与子业态落库，/biz/stores/mine 带回")
    void selfDescribedIndustryIsStoredAndReadBack() throws Exception {
        var mvc = mvc();
        String phone = probePhone();
        login(mvc, phone, "CN");
        merchantAdminPort.quickStart(new MerchantAdminPort.QuickStartCommand(userNoOf(phone), "阿花的店", "中山路 1 号",
                null, null, "花艺", "永生花", "2 楼"));
        assertThat(jdbc.queryForMap("select s.industry_text, s.sub_industry_text, s.address_detail from mch_store s"
                + " join mch_account a on a.entity_no = s.entity_no where a.user_no=?", userNoOf(phone)))
                .containsEntry("industry_text", "花艺").containsEntry("sub_industry_text", "永生花")
                .containsEntry("address_detail", "2 楼");

        // 建店之后重新登录，会话里才有这家店
        String token = login(mvc, phone, null);
        String body = mvc.perform(get("/biz/stores/mine").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode store = json.readTree(body).path("data").path(0).path("stores").path(0);
        assertThat(store.path("industryText").asString()).as(body).isEqualTo("花艺");
        assertThat(store.path("subIndustryText").asString()).as(body).isEqualTo("永生花");
    }

    @Test
    @DisplayName("AC12 手机号账号：profile 的 account 是手机号（与 phone 同格式）")
    void phoneAccountProfileCarriesThePhoneAsAccount() throws Exception {
        var mvc = mvc();
        String phone = probePhone();
        String token = login(mvc, phone, null);
        String body = mvc.perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).path("data");
        assertThat(data.path("account").asString()).as(body).isEqualTo(phone);
        assertThat(data.path("account").asString()).isEqualTo(data.path("phone").asString());
    }
}
