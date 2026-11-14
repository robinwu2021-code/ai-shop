package ai.neargo.inventory.svc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6 T6.3 故障演练里能自动化的部分（上线与回滚手册 §四）：回包丢失、事件重放、失败命令不留痕。
 * 库停与应用重启是人工演练，手册里有步骤。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_drill;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=DEVICE",
        "inventory.instance.allowed-systems=SHAREHUB",
        "inventory.admin.token=admin-test-token"})
class ResilienceDrillTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":[],"targetTypes":[],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[],"gateRefTypes":[],
             "countReview":{"required":false,"reviewerMustDiffer":false}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired @Qualifier("invDataSource") DataSource invDataSource;

    MockMvc mvc;
    JdbcTemplate sql;
    String owner;
    String bearer;
    String item;
    String wh;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        sql = new JdbcTemplate(invDataSource);
        owner = field(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHAREHUB\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"n\"}")), "ownerId");
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + PROFILE + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        bearer = "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
        item = field(write(put("/api/inventory/v1/items/by-ref/CABLE").content("{\"name\":\"线\"}"), key()), "itemId");
        wh = field(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W1\"}"), key()), "locationId");
    }

    @Test
    void lostResponseOnPostIsRetriedWithTheSameKeyAndPostsOnce() throws Exception {
        String no = field(write(post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\",\"lines\":[{\"itemId\":\"%s\",\"qty\":5}]}"
                        .formatted(wh, item)), key()), "docNo");

        // 宿主发了过账、回包丢了 —— 用同一个键重发：拿到第一次的结果，台账只记一次
        String postKey = key();
        String first = body(write(post("/api/inventory/v1/inbounds/" + no + "/post"), postKey)
                .andExpect(status().isOk()).andReturn());
        String retried = body(write(post("/api/inventory/v1/inbounds/" + no + "/post"), postKey)
                .andExpect(status().isOk()).andReturn());
        assertThat(retried).isEqualTo(first);
        assertThat(sql.queryForObject("select count(*) from inv_ledger where owner_id=?", Integer.class, owner))
                .isEqualTo(1);
        JsonNode balance = JSON.readTree(body(read("/api/inventory/v1/balances?itemId=" + item))).get(0);
        assertThat(balance.get("onHand").asString()).isEqualTo("5");
    }

    @Test
    void failedCommandLeavesNoKeyBehindSoTheRetryRunsAgain() throws Exception {
        String failingKey = key();
        write(post("/api/inventory/v1/inbounds/NOPE/post"), failingKey).andExpect(status().isConflict());
        // 失败的命令整笔回滚，连键都不占：重发同一个键会真的再执行，而不是返回一个「空结果」
        assertThat(sql.queryForObject("select count(*) from inv_command where owner_id=? and request_id=?",
                Integer.class, owner, failingKey)).isZero();
        write(post("/api/inventory/v1/inbounds/NOPE/post"), failingKey).andExpect(status().isConflict());
    }

    @Test
    void eventsAreReplayedIdenticallyUntilAcked() throws Exception {
        String no = field(write(post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\",\"lines\":[{\"itemId\":\"%s\",\"qty\":2}]}"
                        .formatted(wh, item)), key()), "docNo");
        write(post("/api/inventory/v1/inbounds/" + no + "/post"), key()).andExpect(status().isOk());

        // 宿主拉完还没确认就崩了：重启后从 0 再拉，拿到的是同一批、同一序号
        JsonNode first = JSON.readTree(body(read("/api/inventory/v1/events?afterSeq=0"))).get("events");
        JsonNode replay = JSON.readTree(body(read("/api/inventory/v1/events?afterSeq=0"))).get("events");
        assertThat(first).isNotEmpty();
        assertThat(replay.findValuesAsString("eventId")).isEqualTo(first.findValuesAsString("eventId"));
        assertThat(replay.findValues("seq")).isEqualTo(first.findValues("seq"));
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    private String field(ResultActions r, String name) throws Exception {
        return JSON.readTree(body(r.andExpect(status().isOk()).andReturn())).get(name).asString();
    }

    private MvcResult read(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions write(MockHttpServletRequestBuilder req, String key) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", key).header("X-Inventory-Actor", "STAFF:E1"));
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
