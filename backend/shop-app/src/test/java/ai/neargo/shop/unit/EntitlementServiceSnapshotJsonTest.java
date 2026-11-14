package ai.neargo.shop.unit;

import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.hash;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.reference;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.snapshot;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.sourceHash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.FrozenSource;
import ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EntitlementServiceSnapshotJsonTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void databaseJsonFormattingAndObjectOrderingDoNotChangeTheSourceFact() {
        var row = row();
        var tree = json.readTree(row.getSourceSnapshot());
        var keys = new ArrayList<String>(); tree.properties().forEach(entry -> keys.add(entry.getKey()));
        Collections.reverse(keys);
        var reordered = json.createObjectNode(); keys.forEach(key -> reordered.set(key, tree.get(key)));
        row.setSourceSnapshot(reordered.toPrettyString());
        assertThat(snapshot(row, json).fact().items().getFirst().amountMinor()).isEqualTo(1);
    }

    @Test void changedAmountIsStillRejected() {
        var row = row();
        row.setSourceSnapshot(row.getSourceSnapshot().replace("\"amountMinor\":1", "\"amountMinor\":2"));
        assertThatThrownBy(() -> snapshot(row, json)).isInstanceOf(BizException.class);
    }

    @Test void changedArrayOrderingIsStillRejected() {
        var row = row();
        row.setSourceSnapshot(row.getSourceSnapshot().replace("[\"CAT300\",\"CAT301\"]", "[\"CAT301\",\"CAT300\"]"));
        assertThatThrownBy(() -> snapshot(row, json)).isInstanceOf(BizException.class);
    }

    @Test void exactLegacyTextSnapshotRemainsReadable() {
        var row = row(); row.setSourceHash(hash(row.getSourceSnapshot()));
        assertThat(snapshot(row, json).fact().userNo()).isEqualTo("BUYER");
    }

    private OrdEntServiceOrder row() {
        var source = new FrozenSource("MAIN", reference("SERVICE1"), "BUYER", "ENTITY", "STORE", "CNY",
                List.of(new UseItem("SL1", "GOODS", "测试服务", 1, 1)), "测试门店", "店员",
                Map.of("SL1", List.of("CAT300", "CAT301")), Instant.parse("2026-10-21T02:00:00Z"));
        var row = new OrdEntServiceOrder(); row.setTenantNo("MAIN"); row.setServiceOrderNo("SERVICE1");
        row.setOperationNo("OPERATION"); row.setUserNo("BUYER"); row.setEntityNo("ENTITY"); row.setStoreNo("STORE");
        row.setSourceSnapshot(json.writeValueAsString(source)); row.setSourceHash(sourceHash(row.getSourceSnapshot(), json));
        row.setContextSnapshot(json.writeValueAsString(new EntitlementContext("MAIN", "OWNER", "request", "0".repeat(64))));
        row.setDeadline(LocalDateTime.parse("2026-10-21T02:15:00")); row.setStatus("CREATED");
        return row;
    }
}
