package ai.neargo.shop.scenario;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.platform.impl.OpsServiceImpl;
import ai.neargo.shop.spi.notify.MailTemplatePort;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * K3（TDD-基础能力库对齐 2a）：运营账号在平台基础库，角色在基础能力库，两库各自绑定后没有共同事务。
 * 建号 = 平台库事务里写账号 + 基础能力库独立事务里写角色；账号这边随后失败（如开通邮件发不出去）就撤回已写的角色。
 * 改角色只写基础能力库，只开基础能力库的事务。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ops-cross-store;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.ops.password-delivery=mail",
})
@ActiveProfiles("test")
@DisplayName("运营账号与角色跨两库写")
class OpsStaffCrossStoreFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private MailTemplatePort mail;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 没有对应账号的有效角色行（角色表是逻辑删除，撤回后行还在、deleted=1） */
    private int orphanRoles() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_member m WHERE m.end_code='OPS' AND m.deleted=0 AND NOT EXISTS "
                + "(SELECT 1 FROM sys_ops_staff s WHERE s.staff_no=m.subject_no)", Integer.class);
    }

    private String create(String token, String username) throws Exception {
        return mvc().perform(post("/ops/staffs").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"realName\":\"新同事\",\"roles\":[\"RISK\"]}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("建号时开通邮件发不出去：账号回滚，已写的角色撤回，不留孤儿角色")
    void mailFailureLeavesNoOrphanRoles() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        int before = orphanRoles();
        doThrow(new IllegalStateException("smtp down")).when(mail)
                .send(any(), any(), any(), any(), any(), any(), any(), any());
        String body;
        try {
            body = create(token, "cross-store-fail@example.com");
        } finally {
            reset(mail);
        }
        assertThat(body).as("邮件失败应让建号失败").doesNotContain("\"code\":0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ops_staff WHERE username=?", Integer.class,
                "cross-store-fail@example.com")).isZero();
        assertThat(orphanRoles()).isEqualTo(before);
    }

    @Test
    @DisplayName("建号成功：账号与角色都在")
    void successfulCreateWritesBoth() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        create(token, "cross-store-ok@example.com");
        String staffNo = jdbc.queryForObject("SELECT staff_no FROM sys_ops_staff WHERE username=?", String.class,
                "cross-store-ok@example.com");
        assertThat(jdbc.queryForList("SELECT role_code FROM sys_role_member WHERE end_code='OPS' AND deleted=0 AND subject_no=?",
                String.class, staffNo)).containsExactly("RISK");
    }

    @Test
    @DisplayName("改角色只写基础能力库：只开基础能力库的事务")
    void roleChangesUseOnlyTheCommonTransaction() throws NoSuchMethodException {
        for (var m : List.of(OpsServiceImpl.class.getMethod("setStaffRole", String.class, String.class),
                OpsServiceImpl.class.getMethod("setStaffRoles", String.class, List.class))) {
            assertThat(m.getAnnotation(Transactional.class)).as(m.getName()).isNotNull();
            assertThat(m.getAnnotation(Transactional.class).transactionManager()).as(m.getName())
                    .isEqualTo(ModuleDataSources.COMMON_TX_MANAGER);
        }
    }
}
