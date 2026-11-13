package ai.neargo.shop.authcaptcha;

import ai.neargo.shop.auth.captcha.*;
import ai.neargo.shop.auth.captcha.impl.BizCaptchaServiceImpl;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.user.service.EmailOtpService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties="shop.auth.captcha.enabled=true")
@ActiveProfiles("test")
class BizCaptchaHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OtpStore otp;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }
    JsonNode postJson(String path, Object body, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) request.header("X-Captcha-Token", token);
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }
    Map<String,Object> password(String principal) {
        return Map.of("grantType","PASSWORD","principal",principal,"credential","wrong-password","agreed",true);
    }
    void required(JsonNode response, String action) {
        assertThat(response.path("code").asInt()).as(response.toString()).isEqualTo(10452);
        assertThat(response.path("data").path("captchaRequired").asBoolean()).isTrue();
        assertThat(response.path("data").path("action").asString()).isEqualTo(action);
    }

    @Test void missingAndUntrustedHeadersCannotBypassPasswordLoginOrIdentityDiscovery() throws Exception {
        for (String path : new String[]{"/biz/auth/login", "/biz/auth/identities"}) {
            required(postJson(path,password("http-password@example.test"),null),"LOGIN");
            required(postJson(path,password("http-password@example.test"),"client-says-success"),"LOGIN");
        }
    }

    @Test void missingPasswordPrincipalIsRejectedByFrameworkValidation() throws Exception {
        var result = postJson("/biz/auth/login", Map.of("grantType","PASSWORD","credential","irrelevant"),null);
        assertThat(result.path("code").asInt()).isEqualTo(10400);
    }

    @Test void smsAndEmailSendsRequireProofWithoutDeliveringAnyMessage() throws Exception {
        String email = "blocked-"+UUID.randomUUID()+"@example.test";
        required(postJson("/biz/auth/email-otp/send",Map.of("email",email),null),"EMAIL_OTP_SEND");
        required(postJson("/biz/auth/otp/send",Map.of("phone","13800000000"),null),"OTP_SEND");
        assertThat(otp.peek(EmailOtpService.key(email))).isEmpty();
        assertThat(otp.peek("13800000000")).isEmpty();
    }

    @Test void otpCredentialLoginAndIdentityEntryRemainOutsideCaptchaPolicy() throws Exception {
        for (String grant : new String[]{"PHONE_OTP","EMAIL_OTP"}) {
            String principal = grant.equals("PHONE_OTP") ? "13900000001" : "no-code@example.test";
            var request = Map.of("grantType",grant,"principal",principal,"credential","wrong","agreed",true);
            assertThat(postJson("/biz/auth/login",request,null).path("code").asInt()).isNotEqualTo(10452);
            assertThat(postJson("/biz/auth/identities",request,null).path("code").asInt()).isNotEqualTo(10452);
        }
        assertThat(postJson("/biz/auth/identities/enter",Map.of("ticket","absent","identityNo","NEW_OWNER"),null)
                .path("code").asInt()).isNotEqualTo(10452);
    }

    @Test void anonymousChallengeAndVerifyUseRealEngineAndFailedPasswordConsumesProof() throws Exception {
        String principal = "proof-"+UUID.randomUUID()+"@example.test";
        var issued = postJson("/biz/auth/captcha/challenge",Map.of("action","LOGIN","principal",principal),null);
        assertThat(issued.path("code").asInt()).as(issued.toString()).isZero();
        var c = json.treeToValue(issued.path("data"), BizCaptchaService.Challenge.class);
        assertThat(issued.path("data").has("randomX")).isFalse();
        String state = jdbc.queryForObject("SELECT payload FROM sys_auth_captcha_state WHERE state_key=?",
                String.class, JdbcCaptchaStore.hash(c.challengeId()));
        int x = Math.round(json.readTree(state).path("validData").path("percentage").floatValue()*c.backgroundImageWidth());
        var track = BizCaptchaServiceTest.track(c,x);
        var verified = postJson("/biz/auth/captcha/verify",Map.of("action","LOGIN","principal",principal,
                "challengeId",c.challengeId(),"track",track),null);
        assertThat(verified.path("code").asInt()).as(verified.toString()).isZero();
        String token = verified.path("data").path("captchaToken").asString();
        assertThat(postJson("/biz/auth/login",password(principal),token).path("code").asInt()).isNotEqualTo(10452);
        required(postJson("/biz/auth/identities",password(principal),token),"LOGIN");
        assertThat(postJson("/biz/auth/captcha/verify",Map.of("action","LOGIN","principal",principal,
                "challengeId",c.challengeId(),"track",track),null).path("code").asInt()).isEqualTo(10452);
    }
}
