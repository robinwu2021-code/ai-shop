package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.gateway.core.resource.ResourceStatusCache;
import ai.neargo.shop.gateway.core.resource.ResourceStore;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 接口资源入库与停用开关（TDD-网关层与统一权限 §2.13、AC25；开发计划 M4b）。
 *
 * <p>测试库在多个上下文之间是同一个，所以每条用例改过的行都在 finally 里改回去。
 */
@SpringBootTest(properties = "shop.gateway.ops.precheck=enforce")
@ActiveProfiles("test")
@DisplayName("网关 · 接口资源入库与停用")
class OpsResourceStoreTest {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final String POLICIES = "/ops/admission/policies";

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;
    @Autowired
    ResourceIndexes indexes;
    @Autowired
    ResourceStore store;
    @Autowired
    ResourceStatusCache statuses;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 启动后库里的行与索引一致；权限码拆成了行")
    void startupSyncWritesEveryScannedLink() {
        List<ApiResource> scanned = indexes.of(List.of("/ops")).all();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_api_resource WHERE end_code = 'ops' AND source = 'SCAN' AND stale = 0",
                Integer.class);
        assertThat(rows).isEqualTo(scanned.size());

        var review = jdbc.queryForMap("SELECT id, access, rule_text, location FROM sys_api_resource"
                + " WHERE end_code = 'ops' AND method = 'POST' AND pattern = '/ops/payments/refunds/{payRefundNo}/review'");
        assertThat(review).containsEntry("access", "POINT").containsEntry("rule_text", "pay:refund:review")
                .containsEntry("location", "LOCAL");
        assertThat(jdbc.queryForList("SELECT perm_code FROM sys_api_resource_point WHERE resource_id = ?",
                String.class, review.get("id"))).containsExactly("pay:refund:review");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_api_resource WHERE end_code = 'pay-ops' AND stale = 0", Integer.class))
                .as("支付运营面是另一条链，单独一组").isEqualTo(indexes.of(List.of("/pay-ops")).all().size());
    }

    @Test
    @DisplayName("★★ 再同步一遍一行不写；少一条就标过期，回来了就恢复 —— 只增改、不删")
    void syncIsIdempotentAndMarksStale() {
        List<ApiResource> scanned = indexes.of(List.of("/ops")).all();
        assertThat(store.syncScanned("ops", scanned, null).unchanged()).as("内容没变不该有任何写入").isTrue();

        try {
            ResourceStore.SyncResult shrunk = store.syncScanned("ops", scanned.subList(1, scanned.size()), null);
            assertThat(shrunk.markedStale()).isEqualTo(1);
            assertThat(shrunk.inserted() + shrunk.updated()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_api_resource WHERE end_code = 'ops'",
                    Integer.class)).as("过期只标记，行还在").isGreaterThanOrEqualTo(scanned.size());
        } finally {
            ResourceStore.SyncResult restored = store.syncScanned("ops", scanned, null);
            assertThat(restored.updated()).isEqualTo(1);
            assertThat(restored.markedStale()).isZero();
        }
    }

    @Test
    @DisplayName("★★★ 停用一条链接：回 404；库里其他列被改不影响判权（判权读的是进程内的索引）")
    void disablingALinkBlocksItAndOtherColumnsAreIgnored() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String operator = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator("STORE-O", "store", List.of(), List.of())));
        String where = " WHERE end_code = 'ops' AND method = 'GET' AND pattern = '" + POLICIES + "'";
        try {
            // 把库里这一行的权限要求改成「公开」：不该有任何效果
            jdbc.update("UPDATE sys_api_resource SET access = 'PUBLIC', rule_text = NULL" + where);
            statuses.invalidate("ops");
            var tampered = mvc.perform(MockMvcRequestBuilders.get(POLICIES)
                    .header("Authorization", "Bearer " + operator)).andReturn().getResponse();
            assertThat(code(tampered.getContentAsString())).as("库里改松了权限，判权不认").isEqualTo(10403);

            jdbc.update("UPDATE sys_api_resource SET status = 'DISABLED'" + where);
            statuses.invalidate("ops");
            assertThat(mvc.perform(MockMvcRequestBuilders.get(POLICIES).header("Authorization", "Bearer " + operator))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(mvc.perform(MockMvcRequestBuilders.get(POLICIES)).andReturn().getResponse().getStatus())
                    .as("没登录也到不了被停用的链接").isEqualTo(404);
        } finally {
            jdbc.update("UPDATE sys_api_resource SET status = 'ENABLED'" + where);
            statuses.invalidate("ops");
            store.syncScanned("ops", indexes.of(List.of("/ops")).all(), null);
        }
        assertThat(mvc.perform(MockMvcRequestBuilders.get(POLICIES)).andReturn().getResponse().getStatus())
                .as("恢复后照常").isEqualTo(401);
    }

    private static int code(String body) {
        Matcher m = CODE.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
