package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.notify.port.StubMailGateway;
import ai.neargo.shop.platform.OpsService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 员工级「免二次验证」白名单：第二因子整体开着（生产只能开着），由超管在员工账号页逐个勾选谁可以只凭密码登录。
 *
 * <p>守四件事：勾了的人只凭密码拿令牌、且不发验证码邮件；没勾 / 取消勾的人照旧要码；
 * 不能给自己勾（否则「能管员工的人」等于能给自己去掉第二因子）；必须写理由（进审计）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsSecondFactorExemptFlowTest {

    private static final String PWD = "Exempt#2026-pass";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher hasher;
    @Autowired private OpsService ops;
    @Autowired private StubMailGateway mail;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;
    private String user;
    private String staffNo;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        secondFactor("off");
        admin = token(login("admin", "admin123"));
        String id = UUID.randomUUID().toString().substring(0, 8);
        user = "ex-" + id + "@neargo.ai";
        staffNo = "ST-EX-" + id;
        jdbc.update("INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, created_at, updated_at)"
                + " SELECT ?, ?, ?, '免验证', roles, 'ACTIVE', NOW(), NOW() FROM sys_ops_staff WHERE username='support'",
                staffNo, user, hasher.encode(PWD));
        secondFactor("email");
    }

    @AfterEach
    void tearDown() {
        secondFactor("off");
    }

    @Test
    @DisplayName("★★★ 勾选免二次验证后：只凭密码拿到令牌，且不发验证码邮件")
    void exemptStaffSkipsSecondFactor() throws Exception {
        JsonNode set = exempt(staffNo, true, "测试账号，需要自动化登录");
        assertThat(set.get("code").asInt()).isEqualTo(0);
        assertThat(set.path("data").path("secondFactorExempt").asBoolean()).isTrue();

        int mailsBefore = mail.all().size();
        JsonNode r = login(user, PWD);
        assertThat(token(r)).as("免验证的人密码对了直接给令牌").isNotBlank();
        assertThat(mail.all().size()).as("不发验证码邮件").isEqualTo(mailsBefore);
        assertThat(jdbc.queryForObject("SELECT second_factor_exempt FROM sys_ops_staff WHERE staff_no=?",
                Integer.class, staffNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 没勾 / 取消勾选：照旧只拿挑战，要邮件里的码；密码错照样拒")
    void nonExemptStillChallenged() throws Exception {
        JsonNode first = login(user, PWD);
        assertThat(token(first)).isNull();
        assertThat(first.path("data").path("challenge").asString()).isNotBlank();

        exempt(staffNo, true, "临时");
        assertThat(token(login(user, "wrong-pass"))).as("免验证不免密码").isNull();
        exempt(staffNo, false, "测试结束收回");
        JsonNode r = login(user, PWD);
        assertThat(token(r)).isNull();
        assertThat(r.path("data").path("challenge").asString()).isNotBlank();
    }

    @Test
    @DisplayName("★★★ 不能给自己勾；不写理由不行；员工列表回显这个标记")
    void guards() throws Exception {
        String adminNo = staffNoOf("admin");
        assertThat(exempt(adminNo, true, "给自己开").get("code").asInt())
                .isEqualTo(ErrorCode.STAFF_SELF_OPERATION.code());
        assertThat(exempt(staffNo, true, "  ").get("code").asInt()).isEqualTo(ErrorCode.BAD_REQUEST.code());

        exempt(staffNo, true, "列表回显");
        JsonNode list = json.readTree(mvc.perform(get("/ops/staffs").param("size", "200")
                .header("Authorization", "Bearer " + admin)).andReturn().getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode s : list.path("data").path("records")) {
            if (staffNo.equals(s.path("staffNo").asString())) {
                found = true;
                assertThat(s.path("secondFactorExempt").asBoolean()).isTrue();
            }
        }
        assertThat(found).as("员工列表里找得到这个人").isTrue();
    }

    @Test
    @DisplayName("★★ 没有员工管理权限的人调不了这个接口")
    void requiresStaffUpdatePermission() throws Exception {
        secondFactor("off");
        String support = token(login("support", "support123"));
        String body = mvc.perform(post("/ops/staffs/" + staffNo + "/second-factor-exempt")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exempt\":true,\"reason\":\"x\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(ErrorCode.FORBIDDEN.code());
    }

    private JsonNode exempt(String target, boolean on, String reason) throws Exception {
        String body = mvc.perform(post("/ops/staffs/" + target + "/second-factor-exempt")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("exempt", on, "reason", reason))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String staffNoOf(String username) {
        return jdbc.queryForObject("SELECT staff_no FROM sys_ops_staff WHERE username=?", String.class, username);
    }

    private JsonNode login(String username, String password) throws Exception {
        String r = mvc.perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(r);
    }

    private static String token(JsonNode r) {
        JsonNode t = r.path("data").path("token");
        return t.isMissingNode() || t.isNull() ? null : t.asString();
    }

    private void secondFactor(String mode) {
        Object target = AopTestUtils.getTargetObject(ops);   // 共用上下文里是代理，改字段要改到目标对象上
        ReflectionTestUtils.setField(target, "secondFactor", mode);
    }
}
