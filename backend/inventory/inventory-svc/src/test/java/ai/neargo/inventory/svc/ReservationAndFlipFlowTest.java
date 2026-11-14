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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 服务侧：预留对外（T8.1、AC-25）、任务服务触发到期回收、按业主翻转写入方与对账（T8.5、AC-27）。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_reservation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=SHOP",
        "inventory.instance.allowed-systems=SHOP",
        "inventory.admin.token=admin-test-token",
        "inventory.jobs.enabled=true",
        "inventory.jobs.internal-token=job-test-token",
        "inventory.flip.drain-millis=0"})
class ReservationAndFlipFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path PROFILE = Path.of("../inventory-domain/src/test/resources/profiles/shop.json");
    private static final String EXPIRY = "/internal/job/inventory-shop-reservation-expiry/run";

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
        owner = id(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHOP\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"店\"}")), "ownerId");
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + Files.readString(PROFILE) + ",\"reason\":\"init\"}"))
                .andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"shop-app\"}")).andExpect(status().isOk()).andReturn()));
        bearer = "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
        item = id(write(put("/api/inventory/v1/items/by-ref/SKU-1").content("{\"name\":\"米\"}")), "itemId");
        wh = id(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"店仓\"}")), "locationId");
        String in = id(write(post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\",\"lines\":[{\"itemId\":\"%s\",\"qty\":10}]}"
                        .formatted(wh, item))), "docNo");
        write(post("/api/inventory/v1/inbounds/" + in + "/post")).andExpect(status().isOk());
    }

    @Test
    void reservationIsAllOrNothingIdempotentAndExpiredByTheJobService() throws Exception {
        // 同单号同内容：同一个预留，不占两份
        JsonNode first = reserve("O-1", 3, 60).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(this::tree);
        JsonNode again = reserve("O-1", 3, 60).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(this::tree);
        assertThat(first.get("status").asString()).isEqualTo("HELD");
        assertThat(again.get("reservationId").asString()).isEqualTo(first.get("reservationId").asString());
        assertThat(reserved()).isEqualByComparingTo("3");
        // 同单号换内容 409；不够全拒
        reserve("O-1", 4, 60).andExpect(status().isConflict());
        reserve("O-2", 8, 60).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.stock.short"));
        assertThat(reserved()).isEqualByComparingTo("3");

        // 取消：释放
        reserve("O-3", 2, 60).andExpect(status().isOk());
        write(post("/api/inventory/v1/reservations/O-3/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        assertThat(reserved()).isEqualByComparingTo("3");

        // 调用方再没回来：到期由任务服务触发回收；密钥不对 401
        sql.update("update inv_reservation set expires_at = ? where owner_id = ? and external_ref = 'O-1'",
                java.time.LocalDateTime.now().minusMinutes(1), owner);
        mvc.perform(post(EXPIRY).header("X-Job-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post(EXPIRY).header("X-Job-Token", "job-test-token")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(get("/api/inventory/v1/reservations/O-1").header("Authorization", bearer))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(reserved()).isZero();
        JsonNode declared = tree(body(mvc.perform(get("/internal/job/declarations").header("X-Job-Token",
                "job-test-token")).andExpect(status().isOk()).andReturn()));
        assertThat(declared.findValuesAsString("handlerName"))
                .contains("inventory-shop-reservation-expiry", "inventory-shop-daily-snapshot");
    }

    @Test
    void expirySweepOnlyTouchesOwnersThisProcessWritesFor() throws Exception {
        reserve("O-9", 1, 60).andExpect(status().isOk());
        sql.update("update inv_reservation set expires_at = ? where owner_id = ?",
                java.time.LocalDateTime.now().minusMinutes(1), owner);
        // 这本账还归内嵌写：独立服务的回收不碰它
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where owner_id = ?", owner);
        mvc.perform(post(EXPIRY).header("X-Job-Token", "job-test-token")).andExpect(status().isOk());
        sql.update("update inv_owner set write_authority = 'SVC' where owner_id = ?", owner);
        assertThat(reserved()).isEqualByComparingTo("1");
    }

    @Test
    void flipReconcilesFirstAndRollsBackWhenTheBooksDoNotBalance() throws Exception {
        // 平：翻到内嵌再翻回，账都在
        JsonNode toEmbedded = flip("EMBEDDED");
        assertThat(toEmbedded.get("writeAuthority").asString()).isEqualTo("EMBEDDED");
        assertThat(toEmbedded.at("/reconciliation/clean").asBoolean()).isTrue();
        write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W2\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.owner.not_writer"));
        assertThat(flip("SVC").get("writeAuthority").asString()).isEqualTo("SVC");
        assertThat(onHand()).isEqualByComparingTo("10");

        // 不平：余额被改坏（不经流水）→ 翻转失败、写入方保持原值，差异列出来
        sql.update("update inv_stock_balance set on_hand = 11 where owner_id = ?", owner);
        JsonNode refused = flip("EMBEDDED");
        assertThat(refused.get("writeAuthority").asString()).isEqualTo("SVC");
        assertThat(refused.at("/reconciliation/clean").asBoolean()).isFalse();
        assertThat(refused.at("/reconciliation/diffs/0/kind").asString()).isEqualTo("LEDGER");
        assertThat(new BigDecimal(refused.at("/reconciliation/diffs/0/expected").asString()))
                .isEqualByComparingTo("10");
        write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W3\"}")).andExpect(status().isOk());

        // 翻转中两边都拒写
        sql.update("update inv_owner set write_authority = 'FLIPPING' where owner_id = ?", owner);
        write(post("/api/inventory/v1/warehouses").content("{\"name\":\"W4\"}")).andExpect(status().isConflict());
        admin(post("/api/inventory/admin/v1/owners/" + owner + "/write-authority/flip").content("{\"to\":\"SVC\"}"))
                .andExpect(status().isConflict());
    }

    private JsonNode flip(String to) throws Exception {
        return tree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/write-authority/flip")
                .content("{\"to\":\"" + to + "\"}")).andExpect(status().isOk()).andReturn()));
    }

    private ResultActions reserve(String ref, int qty, long ttl) throws Exception {
        return write(post("/api/inventory/v1/reservations").content(
                "{\"ref\":\"%s\",\"ttlSeconds\":%d,\"lines\":[{\"itemId\":\"%s\",\"locationId\":\"%s\",\"qty\":%d}]}"
                        .formatted(ref, ttl, item, wh, qty)));
    }

    private BigDecimal reserved() throws Exception {
        return new BigDecimal(balance().get("reserved").asString());
    }

    private BigDecimal onHand() throws Exception {
        return new BigDecimal(balance().get("onHand").asString());
    }

    private JsonNode balance() throws Exception {
        return tree(body(mvc.perform(get("/api/inventory/v1/balances?itemId=" + item).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn())).get(0);
    }

    private JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private ResultActions write(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:E1"));
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private String id(ResultActions r, String field) throws Exception {
        return tree(body(r.andExpect(status().isOk()).andReturn())).get(field).asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
