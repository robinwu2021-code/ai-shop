package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 运营账号的角色只有一处：基础能力库的 {@code sys_role_member}（TDD-基础能力库对齐 2a、Q3）。
 *
 * <p>此前双写：{@code ops_staff.roles} 的 JSON 用来判权，{@code sys_role_member} 用来出菜单。
 * 两处一旦不一致，看到的菜单和能点的按钮就是两套。现在判权也读 {@code sys_role_member}，JSON 列停写。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsRoleSourceFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    @DisplayName("判权按 sys_role_member：JSON 列写成 ADMIN 也不会让人变成超管")
    void rolesComeFromRoleMembers() throws Exception {
        String before = jdbc.queryForObject("SELECT roles FROM ops_staff WHERE username = 'goods'", String.class);
        jdbc.update("UPDATE ops_staff SET roles = '[\"ADMIN\"]' WHERE username = 'goods'");
        try {
            String token = opsLogin("goods", "goods123");
            mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.data.roles[0]").value("GOODS_OPS"));
        } finally {
            jdbc.update("UPDATE ops_staff SET roles = ? WHERE username = 'goods'", before);
        }
    }

    @Test
    @DisplayName("改角色只写 sys_role_member，不再写 ops_staff.roles")
    void changingRoleWritesOnlyRoleMembers() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = jdbc.queryForObject("SELECT staff_no FROM ops_staff WHERE username = 'goods'", String.class);
        String before = jdbc.queryForObject("SELECT roles FROM ops_staff WHERE staff_no = ?", String.class, staffNo);
        try {
            mvc().perform(post("/ops/staffs/" + staffNo + "/role").header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUDITOR\"}"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.roles[0]").value("AUDITOR"));

            assertThat(jdbc.queryForObject("SELECT roles FROM ops_staff WHERE staff_no = ?", String.class, staffNo))
                    .as("JSON 列停写").isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT role_code FROM sys_role_member WHERE end_code = 'OPS' AND subject_no = ?"
                    + " AND deleted = 0", String.class, staffNo)).isEqualTo(List.of("AUDITOR"));
        } finally {
            mvc().perform(post("/ops/staffs/" + staffNo + "/role").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"GOODS_OPS\"}"));
        }
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
