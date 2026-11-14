package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
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
 * 开放 API 加解密的列（开发计划 M3.8；加解密-数据库设计 §4，迁移号按「先合先占」为 V14）：
 * 接入方签名方案、推送冻结的密钥版本、请求记录里的验签版本与加密序列号。开发者控制台的三张表不在本次。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayOpenCryptoColumnsTest {

    @Autowired private ApiRequestRecorder recorder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("★★★ 接入方不写签名方案就是 HMAC（存量接入方全是 HMAC，迁移后行为不变）")
    void signTypeDefaultsToHmac() {
        String appNo = "APP-CRYPTO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps,"
                + " daily_refund_limit_minor, sandbox, created_at, updated_at) VALUES (?, '加解密', 'PARTNER', 'ACTIVE',"
                + " 'pay:read', 10, 0, 1, NOW(), NOW())", appNo);

        assertThat(jdbc.queryForObject("SELECT sign_type FROM pay_app WHERE app_no=?", String.class, appNo))
                .isEqualTo("HMAC");
    }

    @Test
    @DisplayName("★★★ 请求记录：验签命中的版本与 X-Encrypt-Serial 经实体落库、原样读回")
    void requestLogColumnsRoundTrip() {
        String trace = "t-" + UUID.randomUUID();
        PayApiRequestLog row = new PayApiRequestLog();
        row.setTraceId(trace);
        row.setEntry("OPEN_API");
        row.setApi("POST /open/pay/v1/orders");
        row.setPayAction("CREATE_ONLINE");
        row.setSourceType("PARTNER");
        row.setClientType("SERVER");
        row.setOutcome("ACCEPTED");
        row.setReceivedAt(LocalDateTime.now());
        row.setSignKeyVersion(2);
        row.setEncryptSerial("E2026102801");

        recorder.record(row);

        Map<String, Object> back = jdbc.queryForMap(
                "SELECT sign_key_version, encrypt_serial FROM pay_api_request_log WHERE trace_id=?", trace);
        assertThat(back).containsEntry("sign_key_version", 2).containsEntry("encrypt_serial", "E2026102801");
    }

    @Test
    @DisplayName("★★ 推送记录有冻结的两个版本列（重推一律用它们，M3.6）")
    void bizNotifyHasFrozenVersionColumns() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='pay_biz_notify'"
                + " AND column_name IN ('enc_key_version','sign_key_version')", String.class))
                .containsExactlyInAnyOrder("enc_key_version", "sign_key_version");
    }

    @Test
    @DisplayName("★★ 表结构模型（pay-service-database.json，真源）里有这些列 —— 改表先改模型")
    void modelHasTheColumns() throws Exception {
        JsonNode model = json.readTree(Files.readString(Path.of("..", "..", "docs", "technical", "design", "schema",
                "pay-service-database.json")));
        Map<String, List<String>> cols = new HashMap<>();
        for (JsonNode t : model.get("tables")) {
            List<String> names = new java.util.ArrayList<>();
            t.get("columns").forEach(c -> names.add(c.get("name").asString()));
            cols.put(t.get("name").asString(), names);
        }
        assertThat(cols.get("pay_app")).contains("sign_type");
        assertThat(cols.get("pay_biz_notify")).contains("enc_key_version", "sign_key_version");
        assertThat(cols.get("pay_api_request_log")).contains("sign_key_version", "encrypt_serial");
    }
}
