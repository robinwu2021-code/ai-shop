package ai.neargo.shop.scenario;

import ai.neargo.shop.paybridge.ShippingUploadSnapshot;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Snapshot JSON text must remain an object when read back under the H2/MariaDB-compatible schemas. */
@SpringBootTest
@ActiveProfiles("test")
class ShippingSnapshotPersistenceFlowTest {
    @Autowired private ShippingUploadMapper mapper;
    @Autowired private WxShippingUploadService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    void mappedSnapshotRoundTripsAnObjectAndQueueRevisionClearsPriorSuccess() throws Exception {
        String order = "SNAP-" + UUID.randomUUID();
        TrdShippingUpload row = new TrdShippingUpload();
        row.setOrderNo(order);
        row.setOutTradeNo("PAY-" + order);
        row.setLogisticsType(1);
        row.setStatus("PENDING");
        row.setSourceSnapshot("{\"shipments\":[],\"deliveryMode\":2,\"allDelivered\":false,\"blocker\":null}");
        mapper.insert(row);
        TrdShippingUpload read = mapper.selectById(row.getId());
        assertThat(json.readTree(read.getSourceSnapshot()).isObject()).isTrue();
        assertThat(ShippingUploadSnapshot.parse(read.getSourceSnapshot(), json).allDelivered()).isFalse();
        Object raw = jdbc.queryForObject("SELECT source_snapshot FROM trd_shipping_upload WHERE id=?",
                Object.class, row.getId());
        String persisted = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : raw instanceof java.sql.Clob clob ? clob.getSubString(1, (int) clob.length()) : raw.toString();
        assertThat(json.readTree(persisted).isObject()).isTrue();
        service.enqueue(order, row.getOutTradeNo(), 1);
        TrdShippingUpload refreshed = mapper.selectOne(Wrappers.<TrdShippingUpload>lambdaQuery()
                .eq(TrdShippingUpload::getOrderNo, order));
        assertThat(refreshed.getVersion()).isGreaterThan(read.getVersion());
        assertThat(refreshed.getSourceSnapshot()).isNull();
    }
}
