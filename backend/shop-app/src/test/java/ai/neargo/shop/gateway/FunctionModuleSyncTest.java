package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.neargo.shop.gateway.core.function.FunctionRegistry;
import ai.neargo.shop.gateway.core.function.ModuleOwnership;
import ai.neargo.shop.gateway.iam.function.FunctionModuleSync;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 功能点的模块归属入库，以及菜单按「模块装没装」降级（TDD-网关层与统一权限 §2.6；开发计划 F 第二步）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关 · 功能点模块归属入库")
class FunctionModuleSyncTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired FunctionModuleSync sync;
    @Autowired FunctionRegistry registry;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("★★★ 启动后每个带权限码的功能点都有归属；再同步一遍一行不写")
    void everyPointWithACodeHasAModule() {
        assertThat(jdbc.queryForObject("select count(*) from sys_function_point where perm_code is not null"
                + " and perm_code <> '' and module_code is null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_function_point where (perm_code is null"
                + " or perm_code = '') and module_code is not null", Integer.class)).isZero();
        assertThat(sync.sync()).as("内容没变不该再写").isZero();

        Map<String, Object> job = jdbc.queryForMap("select module_code from sys_function_point"
                + " where perm_code = 'system:job:read' limit 1");
        assertThat(job.get("module_code")).isEqualTo(ModuleOwnership.JOB);
        List<Map<String, Object>> byModule = jdbc.queryForList("select module_code, count(*) c from sys_function_point"
                + " where module_code is not null group by module_code order by module_code");
        System.out.println("功能点表按模块：" + byModule);
        // 这一步真正改变行为的范围：模块没装、而此前又没有开关门着的菜单点
        System.out.println("新降级的菜单点：" + jdbc.queryForList("select module_code, name, href from sys_function_point"
                + " where point_type = 'MENU' and (gated_by is null or gated_by = '') and module_code in"
                + " ('commerce','print','job','inventory') order by module_code, sort").stream()
                .filter(row -> !registry.modules().contains((String) row.get("module_code")))
                .map(row -> row.get("module_code") + " " + row.get("name") + " " + row.get("href")).toList());
    }

    @Test
    @DisplayName("★★ 归属被人改错了：下一次同步改回来")
    void wrongOwnershipIsCorrected() {
        jdbc.update("update sys_function_point set module_code = 'wrong' where perm_code = 'system:param:read'");
        assertThat(sync.sync()).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from sys_function_point where module_code = 'wrong'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("★★★ 菜单：模块没装的点降为「现在用不了」，装了的不受影响")
    void menuDegradesPointsOfAbsentModules() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        JsonNode menu = json.readTree(mvc.perform(get("/ops/menu").header("Authorization",
                "Bearer " + TestLogin.admin(mvc, json))).andReturn().getResponse().getContentAsString());
        assertThat(menu.path("code").asInt()).as(menu.toString().substring(0, Math.min(300, menu.toString().length())))
                .isZero();
        int checked = 0;
        for (JsonNode function : menu.path("data")) {
            for (JsonNode point : function.path("points")) {
                String code = point.path("permCode").asString("");
                if (code.isBlank()) {
                    continue;
                }
                String module = ModuleOwnership.moduleOf(ai.neargo.shop.auth.Realm.OPERATOR, code);
                if (!registry.modules().contains(module)) {
                    assertThat(point.path("backendStatus").asString())
                            .as("%s 属于没装的模块 %s", code, module).isEqualTo("NOT_IMPLEMENTED");
                    checked++;
                }
            }
        }
        System.out.println("菜单里属于未装配模块的点：" + checked + "；已装配模块 " + registry.modules());
        assertThat(checked).as("测试配置下定时任务等模块是关的，菜单里应当有它们的点").isPositive();
    }
}
