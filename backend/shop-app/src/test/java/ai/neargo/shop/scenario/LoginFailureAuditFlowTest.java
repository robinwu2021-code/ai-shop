package ai.neargo.shop.scenario;

import ai.neargo.auth.store.LoginLogDao;
import ai.neargo.shop.config.SessionProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <b>登录失败要留痕。</b>
 *
 * <p>成功与登出在 {@code TokenStore} 的签发/撤销处自动落，唯独失败走不到那里 ——
 * 而登录是最容易被刷的接口之一，<b>失败日志是被刷时唯一的证据</b>。
 * 在这之前，这个系统一条失败登录记录都没有。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("登录失败审计")
class LoginFailureAuditFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient authJdbcClient;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private List<ai.neargo.auth.store.LoginLogRow> failures(
            ai.neargo.auth.store.SessionProfile p) {
        return new LoginLogDao(authJdbcClient, p)
                .recentFailures(LocalDateTime.now().minusMinutes(5), 50);
    }

    @Test
    @DisplayName("★ 运营端密码错 → 落一条 LOGIN_FAILED，带错误码与打码后的登录名")
    void operatorBadPasswordIsAudited() throws Exception {
        int before = failures(SessionProfiles.OPERATOR).size();

        mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"绝对不对的密码\"}"));

        List<ai.neargo.auth.store.LoginLogRow> after = failures(SessionProfiles.OPERATOR);
        assertThat(after).hasSize(before + 1);
        assertThat(after.get(0).event()).isEqualTo("LOGIN_FAILED");
        assertThat(after.get(0).success()).isFalse();
        assertThat(after.get(0).reason())
                .as("记错误码而不是给用户看的那句话 —— 「密码错误」与「账号被停用」"
                    + "在排查时是两件事，而给用户的提示常常是同一句")
                .isNotBlank();
        assertThat(after.get(0).userNo()).isEqualTo("admin");
    }

    @Test
    @DisplayName("★★ C 端手机号要打码 —— 审计表将来会被多方读，完整号码不该在里面躺着")
    void consumerPhoneIsMasked() throws Exception {
        mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"13812345678\","
                         + "\"credential\":\"000000\",\"agreed\":true}"));

        List<ai.neargo.auth.store.LoginLogRow> rows = failures(SessionProfiles.CONSUMER);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).userNo())
                .as("完整手机号进了审计表")
                .isEqualTo("138****5678");
    }

    @Test
    @DisplayName("★ B 端的失败记进 MERCHANT 池 —— 混进 C 端那张表就看不出「谁在刷商家登录」")
    void merchantFailureGoesToItsOwnLog() throws Exception {
        int consumerBefore = failures(SessionProfiles.CONSUMER).size();

        mvc().perform(post("/biz/auth/staff-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13700007777\",\"code\":\"000000\"}"));

        assertThat(failures(SessionProfiles.MERCHANT)).isNotEmpty();
        assertThat(failures(SessionProfiles.CONSUMER))
                .as("B 端的失败漏进了 C 端的审计表")
                .hasSize(consumerBefore);
    }

    @Test
    @DisplayName("成功登录不落失败记录")
    void successIsNotAFailure() throws Exception {
        int before = failures(SessionProfiles.OPERATOR).size();
        ai.neargo.shop.support.TestLogin.admin(mvc(),
                context.getBean(tools.jackson.databind.ObjectMapper.class));
        assertThat(failures(SessionProfiles.OPERATOR)).hasSize(before);
    }
}
