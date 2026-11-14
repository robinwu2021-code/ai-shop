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

@SpringBootTest(properties={"shop.auth.captcha.enabled=true",
        "shop.otp.adaptive.scenes[ORDER_PICKUP_CONFIRM].enabled=true",
        "shop.otp.adaptive.scenes[ORDER_PICKUP_CONFIRM].entries[0]=ORDER_TEST"})
@ActiveProfiles("test")
class BizCaptchaHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OtpStore otp;
    @Autowired ai.neargo.shop.common.otp.service.OtpSendService sends;
    @Autowired ai.neargo.shop.common.otp.policy.InMemoryOtpFrequency frequency;
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
        for (int i=0;i<3;i++) frequency.record(EmailOtpService.key(email));
        for (int i=0;i<3;i++) frequency.record("13800000000");
        var response = postJson("/biz/auth/email-otp/send",Map.of("email",email),null);
        required(response,"EMAIL_OTP_SEND");
        assertThat(response.path("data").path("scene").asString()).isEqualTo("LOGIN");
        required(postJson("/biz/auth/otp/send",Map.of("phone","13800000000"),null),"OTP_SEND");
        assertThat(otp.peek(EmailOtpService.key(email))).isEmpty();
        assertThat(otp.peek("13800000000")).isEmpty();
    }

    @Test void firstEmailSendUsesExistingStubWithoutSliderAndStoresCode() throws Exception {
        String email = "low-"+UUID.randomUUID()+"@example.test";
        var result = postJson("/biz/auth/email-otp/send",Map.of("email",email),null);
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        assertThat(result.path("data").path("expiresInSeconds").asInt()).isEqualTo(600);
        assertThat(otp.peek(EmailOtpService.key(email))).isPresent();
    }

    @Test void challengeRejectsDisabledScenesAndLoginBusinessKeys() throws Exception {
        assertThat(postJson("/biz/auth/captcha/challenge",Map.of("action","OTP_SEND","principal","13800000000",
                "scene","PASSWORD_RESET"),null).path("code").asInt()).isEqualTo(10400);
        assertThat(postJson("/biz/auth/captcha/challenge",Map.of("action","OTP_SEND","principal","13800000000",
                "scene","LOGIN","businessKey","order1"),null).path("code").asInt()).isEqualTo(10400);
    }

    @Test void enabledBusinessSceneRoundTripsThroughHttpAndRetiresProofOnRetry() throws Exception {
        String phone = "13900000009";
        String scene = "ORDER_PICKUP_CONFIRM";
        String business = "order-"+UUID.randomUUID();
        var caller = new ai.neargo.shop.common.otp.service.OtpSendService.OtpContext("ORDER_TEST","test-ip",null);
        var command = new ai.neargo.shop.common.otp.service.OtpSendService.SendCommand(scene,
                ai.neargo.shop.common.otp.service.OtpSendService.Channel.SMS,phone,business,null,null,null);
        frequency.record(phone);
        assertThatThrownBy(() -> sends.send(command,caller))
                .isInstanceOf(ai.neargo.shop.common.captcha.CaptchaRequiredException.class);
        var issued = postJson("/biz/auth/captcha/challenge",Map.of("action","OTP_SEND","principal",phone,
                "scene",scene,"businessKey",business),null);
        assertThat(issued.path("code").asInt()).as(issued.toString()).isZero();
        var c = json.treeToValue(issued.path("data"),BizCaptchaService.Challenge.class);
        String payload = jdbc.queryForObject("SELECT payload FROM sys_auth_captcha_state WHERE state_key=?",String.class,JdbcCaptchaStore.hash(c.challengeId()));
        int x = Math.round(json.readTree(payload).path("validData").path("percentage").floatValue()*c.backgroundImageWidth());
        var verified = postJson("/biz/auth/captcha/verify",Map.of("challengeId",c.challengeId(),"action","OTP_SEND","principal",phone,
                "scene",scene,"businessKey",business,"track",BizCaptchaServiceTest.track(c,x)),null);
        assertThat(verified.path("code").asInt()).as(verified.toString()).isZero();
        String proof = verified.path("data").path("captchaToken").asString();
        var retry = new ai.neargo.shop.common.otp.service.OtpSendService.SendCommand(scene,
                command.channel(),phone,business,null,null,proof);
        assertThat(sends.send(retry,caller).expiresInSeconds()).isEqualTo(300);
        String key = ai.neargo.shop.common.otp.OtpKeys.storageKey(scene,command.channel(),phone,business);
        String code = otp.peek(key).orElseThrow();
        assertThatThrownBy(() -> sends.send(retry,caller)).isInstanceOf(ai.neargo.shop.common.captcha.CaptchaRequiredException.class);
        assertThat(otp.peek(key)).contains(code);
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
