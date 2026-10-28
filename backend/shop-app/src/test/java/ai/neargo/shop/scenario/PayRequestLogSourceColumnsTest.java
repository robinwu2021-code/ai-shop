package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V13 请求记录加来源（开发计划 M2.5；TDD-支付服务-接入守卫模块pay-guard-代码与数据库 §3.1）：
 * 8 个来源列经实体落库、原样读回；表结构模型（真源 JSON）里也有这 8 列。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayRequestLogSourceColumnsTest {

    private static final List<String> SOURCE_COLUMNS = List.of("source_service", "source_module", "source_instance",
            "source_ip", "origin_api", "origin_client", "origin_ip", "origin_ua_digest");

    @Autowired private ApiRequestRecorder recorder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("★★★ 一行内部调用记录：8 个来源列经实体写入、原样读回")
    void sourceColumnsRoundTrip() {
        String trace = "t-" + UUID.randomUUID();
        PayApiRequestLog row = new PayApiRequestLog();
        row.setTraceId(trace);
        row.setEntry("INTERNAL_PORT");
        row.setApi("PaymentPort#createPayment");
        row.setPayAction("CREATE_ONLINE");
        row.setSourceType("INTERNAL");
        row.setClientType("SERVER");
        row.setOutcome("ACCEPTED");
        row.setReceivedAt(LocalDateTime.now());
        row.setSourceService("shop-app");
        row.setSourceModule("MALL");
        row.setSourceInstance("api-01");
        row.setSourceIp("10.0.1.12");
        row.setOriginApi("POST /mp/orders/{orderNo}/pay");
        row.setOriginClient("APP_C");
        row.setOriginIp("223.104.1.1");
        row.setOriginUaDigest("ab".repeat(32));

        recorder.record(row);

        Map<String, Object> back = jdbc.queryForMap("SELECT " + String.join(",", SOURCE_COLUMNS)
                + " FROM pay_api_request_log WHERE trace_id=?", trace);
        assertThat(back).containsEntry("source_service", "shop-app").containsEntry("source_module", "MALL")
                .containsEntry("source_instance", "api-01").containsEntry("source_ip", "10.0.1.12")
                .containsEntry("origin_api", "POST /mp/orders/{orderNo}/pay").containsEntry("origin_client", "APP_C")
                .containsEntry("origin_ip", "223.104.1.1").containsEntry("origin_ua_digest", "ab".repeat(32));
    }

    @Test
    @DisplayName("★★ 表结构模型（pay-service-database.json，真源）里有这 8 列 —— 改表先改模型")
    void modelHasTheColumns() throws Exception {
        JsonNode model = json.readTree(Files.readString(Path.of("..", "..", "docs", "technical", "design", "schema",
                "pay-service-database.json")));
        List<String> cols = new ArrayList<>();
        for (JsonNode t : model.get("tables")) {
            if ("pay_api_request_log".equals(t.get("name").asString())) {
                t.get("columns").forEach(c -> cols.add(c.get("name").asString()));
            }
        }
        assertThat(cols).containsAll(SOURCE_COLUMNS);
    }
}
