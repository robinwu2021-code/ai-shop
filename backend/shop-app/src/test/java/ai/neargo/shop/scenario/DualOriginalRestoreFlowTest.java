package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.invbridge.InvMirrorEvent;
import ai.neargo.shop.invbridge.InventoryMirrorConsumer;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.StockPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** Real outbox consumer and inventory postings; queue acceptance is not stock recovery completion. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-dual-primary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.inventory.datasource.url=jdbc:h2:mem:refund-dual-inv;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.inventory.stock-authority=DUAL"
})
@ActiveProfiles("test")
class DualOriginalRestoreFlowTest {
    private static final String RESTORE = "INV_MIRROR_RESTORE_ORIGINAL";
    @Autowired private StockPort stock;
    @Autowired private SkuMapper skus;
    @Autowired private SysOutboxMapper events;
    @Autowired private InventoryMirrorConsumer consumer;
    @Autowired private InventoryAclService acl;
    @Autowired private InboundService inbound;
    @Autowired private StockQueryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    void waitsForActualOriginalPostingEvenAfterManagedSwitchChanges() {
        String old = mode();
        try {
            mode("ON");
            Fixture f = fixture();
            stock.lock(f.ref(), f.items());
            consumer.consume(event(f.ref(), InvMirrorEvent.RESERVE));
            stock.confirm(f.ref());
            consumer.consume(event(f.ref(), InvMirrorEvent.COMMIT));
            assertThat(onHand(f)).isEqualTo(7);
            mode("OFF");
            assertThatCode(() -> stock.restoreOriginal(f.purpose(), f.ref(), f.items()))
                    .doesNotThrowAnyException();
            assertThat(stock.restoreOriginal(f.purpose(), f.ref(), f.items()))
                    .isEqualTo(StockPort.RestoreResult.PENDING);
            assertThat(platform(f)).isEqualTo(10);
            assertThat(onHand(f)).isEqualTo(7);
            SysOutbox restore = event(f.purpose(), RESTORE);
            assertThat(restore).isNotNull();
            consumer.consume(restore);
            consumer.consume(restore);
            assertThat(stock.restoreOriginal(f.purpose(), f.ref(), f.items()))
                    .isEqualTo(StockPort.RestoreResult.COMPLETED);
            assertThat(onHand(f)).isEqualTo(10);
            assertThat(platform(f)).isEqualTo(10);
            assertThat(events.selectCount(Wrappers.<SysOutbox>lambdaQuery()
                    .eq(SysOutbox::getAggregateId, f.purpose()).eq(SysOutbox::getEventType, RESTORE))).isEqualTo(1);
        } finally {
            mode(old);
        }
    }

    @Test
    void emptyOriginalManagedSnapshotCompletesPlatformOnlyWithoutInventoryClaim() {
        String old = mode();
        try {
            mode("OFF");
            Fixture f = fixture();
            stock.lock(f.ref(), f.items());
            stock.confirm(f.ref());
            SysOutbox original = event(f.ref(), InvMirrorEvent.RESERVE);
            assertThat(original).as("Empty original managed-set proof must be durable").isNotNull();
            assertThat(json.readTree(original.getPayload()).path("items").size()).isZero();
            mode("ON");
            assertThat(stock.restoreOriginal(f.purpose(), f.ref(), f.items()))
                    .isEqualTo(StockPort.RestoreResult.COMPLETED);
            assertThat(stock.restoreOriginal(f.purpose(), f.ref(), f.items()))
                    .isEqualTo(StockPort.RestoreResult.COMPLETED);
            assertThat(platform(f)).isEqualTo(10);
            assertThat(onHand(f)).isEqualTo(10);
            assertThat(event(f.purpose(), RESTORE)).isNull();
        } finally {
            mode(old);
        }
    }

    @Test
    void absentHistoricalManagedSnapshotCannotBeReplacedByCurrentConfiguration() {
        Fixture f = fixture();
        stock.lock(f.ref(), f.items());
        stock.confirm(f.ref());
        events.delete(Wrappers.<SysOutbox>lambdaQuery().eq(SysOutbox::getAggregateId, f.ref()));
        assertThatThrownBy(() -> stock.restoreOriginal(f.purpose(), f.ref(), f.items()))
                .isInstanceOf(IllegalStateException.class).hasMessage("STOCK_SOURCE_MIRROR_MISSING");
        assertThat(platform(f)).isEqualTo(7);
    }

    private Fixture fixture() {
        String ref = UUID.randomUUID().toString();
        String skuNo = "SKU-" + ref;
        PrdSku sku = new PrdSku();
        sku.setSkuNo(skuNo);
        sku.setGoodsNo("G0002");
        sku.setEntityNo("M0001");
        sku.setMarket("CN");
        sku.setStock(10);
        sku.setLockedStock(0);
        sku.setPrice(4200L);
        skus.insert(sku);
        String owner = acl.ownerIdOf("M0001");
        String item = acl.upsertItem("M0001", skuNo, "refund stock", "unit", null, null, "BAG");
        String location = acl.locationIdOf("M0001", null);
        inbound.postDirectly(new InboundService.Draft(owner, location, "PURCHASE", null, null, "supplier",
                LocalDateTime.now(), null, List.of(new InboundService.Line(item, 10, "BAG", 4200L))), "TEST");
        return new Fixture(ref, skuNo, owner, item);
    }

    private int onHand(Fixture f) { return inventory.itemDetail(f.owner(), f.item()).onHand(); }
    private int platform(Fixture f) {
        return skus.selectOne(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, f.sku())).getStock();
    }
    private SysOutbox event(String ref, String type) {
        return events.selectOne(Wrappers.<SysOutbox>lambdaQuery().eq(SysOutbox::getAggregateId, ref)
                .eq(SysOutbox::getEventType, type));
    }
    private String mode() { return jdbc.queryForObject("select inv_mode from prd_goods where goods_no='G0002'",
            String.class); }
    private void mode(String value) { jdbc.update("update prd_goods set inv_mode=? where goods_no='G0002'", value); }
    private record Fixture(String ref, String sku, String owner, String item) {
        String purpose() { return "RF-" + ref; }
        List<StockPort.SkuQty> items() { return List.of(new StockPort.SkuQty(sku, 3)); }
    }
}
