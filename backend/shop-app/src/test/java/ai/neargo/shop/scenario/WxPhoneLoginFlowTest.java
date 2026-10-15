package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.marketing.FissionPort;
import ai.neargo.shop.spi.user.UserIdentityPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Exercise real phone account resolution and token issuance; mock only the external authorization port. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:wx_phone_login;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class WxPhoneLoginFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private TokenStore tokens;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserIdentityPort identities;
    @MockitoBean private WxPhonePort phones;
    @MockitoBean private WxAuthPort wxAuth;

    @BeforeEach
    void verifiedWxCodes() {
        when(wxAuth.codeToSession(anyString()))
                .thenAnswer(invocation -> new WxAuthPort.WxSession(invocation.getArgument(0), null));
    }
    @MockitoBean private FissionPort fission;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String mini(String openid) throws Exception {
        return TestLogin.consumerByWechat(mvc(), json, openid);
    }

    private JsonNode login(String token, String code) throws Exception {
        String body = mvc().perform(post("/mp/user/login/phone")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneCode\":\"" + code + "\",\"agreed\":true,\"inviterNo\":\"invite-phone\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void newPhoneRegistersOnce() throws Exception {
        String technical = mini("phone-login-new");
        String userNo = tokens.get(technical).orElseThrow().user().userNo();
        when(phones.phoneOf("new-phone-1")).thenReturn("17801414001");
        when(phones.phoneOf("new-phone-2")).thenReturn("17801414001");
        JsonNode first = login(technical, "new-phone-1");
        assertThat(first.path("code").asInt()).isZero();
        assertThat(first.at("/data/isNewUser").asBoolean()).isTrue();
        assertThat(first.at("/data/user/userNo").asText()).isEqualTo(userNo);
        JsonNode second = login(first.at("/data/token").asText(), "new-phone-2");
        assertThat(second.path("code").asInt()).isZero();
        assertThat(second.at("/data/isNewUser").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from usr_identity where identity_type='PHONE' "
                + "and identity_value='17801414001'", Integer.class)).isEqualTo(1);
        verify(fission, times(1)).onRegister(eq("invite-phone"), eq(userNo), isNull(), eq("4001"));
    }

    @Test
    void existingPhoneReturnsOriginalAccount() throws Exception {
        String ownerToken = TestLogin.consumer(mvc(), json, otp, "17801414002");
        String owner = tokens.get(ownerToken).orElseThrow().user().userNo();
        when(phones.phoneOf("existing-phone")).thenReturn("17801414002");
        JsonNode result = login(mini("phone-login-existing"), "existing-phone");
        assertThat(result.path("code").asInt()).isZero();
        assertThat(result.at("/data/isNewUser").asBoolean()).isFalse();
        assertThat(result.at("/data/user/userNo").asText()).isEqualTo(owner);
        assertThat(tokens.get(result.at("/data/token").asText()).orElseThrow().user().userNo())
                .isEqualTo(owner);
    }

    @Test
    void invalidCodeDoesNotIssueSession() throws Exception {
        String token = mini("phone-login-invalid");
        String userNo = tokens.get(token).orElseThrow().user().userNo();
        JsonNode result = login(token, "expired-phone");
        assertThat(result.path("code").asInt()).isEqualTo(70027);
        assertThat(result.at("/data/token").isMissingNode()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from usr_identity where user_no=? "
                + "and identity_type='PHONE'", Integer.class, userNo)).isZero();
    }

    @Test
    void blockedAccountCannotRegisterAgain() throws Exception {
        String ownerToken = TestLogin.consumer(mvc(), json, otp, "17801414003");
        String owner = tokens.get(ownerToken).orElseThrow().user().userNo();
        jdbc.update("update usr_account set status='BANNED' where user_no=?", owner);
        when(phones.phoneOf("blocked-phone")).thenReturn("17801414003");
        JsonNode result = login(mini("phone-login-blocked"), "blocked-phone");
        assertThat(result.path("code").asInt()).isNotZero();
        assertThat(result.at("/data/token").isMissingNode()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from usr_identity where identity_type='PHONE' "
                + "and identity_value='17801414003'", Integer.class)).isEqualTo(1);
    }

    @Test
    void openidConflictPreservesAssets() throws Exception {
        String ownerToken = TestLogin.consumer(mvc(), json, otp, "17801414004");
        String owner = tokens.get(ownerToken).orElseThrow().user().userNo();
        String technical = mini("phone-login-conflict");
        String original = tokens.get(technical).orElseThrow().user().userNo();
        jdbc.update("update usr_account set nickname='retain-me' where user_no=?", original);
        when(phones.phoneOf("conflict-phone")).thenReturn("17801414004");
        JsonNode result = login(technical, "conflict-phone");
        assertThat(result.path("code").asInt()).isZero();
        assertThat(result.at("/data/user/userNo").asText()).isEqualTo(owner);
        assertThat(result.at("/data/wxRelation").asText()).isEqualTo("pending");
        assertThat(jdbc.queryForObject("select user_no from usr_identity where identity_type='WX_OPENID_MP' "
                + "and identity_value='phone-login-conflict'", String.class)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select nickname from usr_account where user_no=?", String.class, original))
                .isEqualTo("retain-me");
    }

    @Test
    void conflictingWechatCredentialsDoNotReassignIdentities() throws Exception {
        String ownerToken = TestLogin.consumer(mvc(), json, otp, "17801414008");
        String owner = tokens.get(ownerToken).orElseThrow().user().userNo();
        String miniToken = mini("phone-login-union-mini");
        String original = tokens.get(miniToken).orElseThrow().user().userNo();
        jdbc.update("insert into usr_identity(user_no,identity_type,identity_value,channel,verified_at,"
                + "created_at,updated_at) values (?, 'WX_UNIONID', 'phone-login-union-existing', 'APP', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", owner);
        when(wxAuth.codeToSession("union-fresh-code"))
                .thenReturn(new WxAuthPort.WxSession("phone-login-union-mini", "phone-login-union-existing"));
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantType\":\"WX_MINI\",\"principal\":\"union-fresh-code\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode technical = json.readTree(body);
        // Contradictory permanent credentials require manual cleanup, never automatic ownership transfer.
        assertThat(technical.path("code").asInt()).isEqualTo(10409);
        assertThat(technical.at("/data/token").isMissingNode()).isTrue();
        assertThat(jdbc.queryForObject("select user_no from usr_identity where identity_type='WX_UNIONID' "
                + "and identity_value='phone-login-union-existing'", String.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("select user_no from usr_identity where identity_type='WX_OPENID_MP' "
                + "and identity_value='phone-login-union-mini'", String.class)).isEqualTo(original);
    }

    @Test
    void nonMiniSessionCannotAuthorizePhone() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "17801414006");
        when(phones.phoneOf("not-mini-phone")).thenReturn("17801414007");
        JsonNode result = login(token, "not-mini-phone");
        assertThat(result.path("code").asInt()).isEqualTo(10401);
    }

    @Test
    void payerUsesCurrentWechatContext() throws Exception {
        String ownerToken = TestLogin.consumer(mvc(), json, otp, "17801414005");
        String owner = tokens.get(ownerToken).orElseThrow().user().userNo();
        when(phones.phoneOf("payer-phone")).thenReturn("17801414005");
        JsonNode result = login(mini("phone-login-current-payer"), "payer-phone");
        assertThat(result.path("code").asInt()).isZero();
        String token = result.at("/data/token").asText();
        LoginUser user = tokens.get(token).orElseThrow().user();
        var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
        authentication.setDetails(token);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        assertThat(identities.wxPayerOpenIdMp(owner)).contains("phone-login-current-payer");
        assertThat(identities.wxOpenIdMp(owner)).isEmpty();
    }
}
