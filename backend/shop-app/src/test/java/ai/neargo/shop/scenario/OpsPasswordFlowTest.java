package ai.neargo.shop.scenario;

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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端密码：bcrypt + 存量平滑升级。
 *
 * <p><b>这批的风险不在「新密码对不对」，在「存量账号会不会被锁在门外」。</b>
 * 一个把人锁死的升级逻辑，故障发生在「用户输对了密码」的那一刻 ——
 * 最难让人相信是系统的问题，也最难从日志里看出来。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("运营端密码 · bcrypt 与存量升级")
class OpsPasswordFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 一期占位哈希 —— 用来把账号**打回存量状态**，模拟升级前的库。 */
    private static String legacy(String raw) {
        return Integer.toHexString(("shop$" + raw).hashCode());
    }

    private String storedPassword(String username) {
        return jdbc.queryForObject(
                "SELECT password FROM sys_ops_staff WHERE username = ?", String.class, username);
    }

    private int loginCode(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asInt();
    }

    @Test
    @DisplayName("★★★ 存量（旧格式）账号能登录，且登录后就地升级成 bcrypt")
    void legacyPasswordLoginsAndUpgrades() throws Exception {
        String before = storedPassword("support");
        try {
            // 打回存量状态：库里是一期占位哈希
            jdbc.update("UPDATE sys_ops_staff SET password = ? WHERE username = 'support'",
                    legacy("support123"));
            assertThat(storedPassword("support")).doesNotStartWith("$2");

            assertThat(loginCode("support", "support123"))
                    .as("存量账号必须还能登录 —— 否则升级那天全员被锁在门外").isZero();

            assertThat(storedPassword("support"))
                    .as("登录成功之后应当就地升级成 bcrypt").startsWith("$2");

            // **升级之后还要能再登录**：只升级不能用，等于换了个方式把人锁死
            assertThat(loginCode("support", "support123")).isZero();
        } finally {
            jdbc.update("UPDATE sys_ops_staff SET password = ? WHERE username = 'support'", before);
        }
    }

    @Test
    @DisplayName("★★★ 旧格式下密码错误：不放行，也**不改库** —— 否则把错密码写进去")
    void wrongPasswordNeverUpgrades() throws Exception {
        String before = storedPassword("support");
        try {
            String stale = legacy("support123");
            jdbc.update("UPDATE sys_ops_staff SET password = ? WHERE username = 'support'", stale);

            assertThat(loginCode("support", "wrong-password"))
                    .as("密码错就该拒绝").isNotZero();
            assertThat(storedPassword("support"))
                    .as("验证失败绝不能触发升级 —— 那等于把用户输错的那串写进库")
                    .isEqualTo(stale);
        } finally {
            jdbc.update("UPDATE sys_ops_staff SET password = ? WHERE username = 'support'", before);
        }
    }

    @Test
    @DisplayName("★★ 种子账号本来就是 bcrypt —— 种子不该产出「待升级」的存量")
    void seededAccountsAreBcrypt() {
        for (String u : new String[]{"admin", "bd", "goods", "support"}) {
            assertThat(storedPassword(u)).as("%s 的密码应当是 bcrypt", u).startsWith("$2");
        }
    }

    @Test
    @DisplayName("★★ 改密之后是 bcrypt，且旧密码立刻失效")
    void changePasswordStoresBcrypt() throws Exception {
        String before = storedPassword("support");
        String token = json.readTree(mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"support\",\"password\":\"support123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data").get("token").asString();
        try {
            mvc().perform(post("/ops/staffs/me/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"oldPassword\":\"support123\",\"newPassword\":\"newpass2026\"}"))
                    .andExpect(jsonPath("$.code").value(0));

            assertThat(storedPassword("support")).startsWith("$2");
            assertThat(loginCode("support", "newpass2026")).isZero();
            assertThat(loginCode("support", "support123"))
                    .as("旧密码必须立刻失效").isNotZero();
        } finally {
            jdbc.update("UPDATE sys_ops_staff SET password = ? WHERE username = 'support'", before);
        }
    }
}
