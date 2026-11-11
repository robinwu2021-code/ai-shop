package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Change;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** D2-04/06: public retail entry orchestration on real MySQL9 counters; the trusted source service is a double. */
@EnabledIfEnvironmentVariable(named="I04B_NATIVE_MYSQL", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailStockReplenishmentEntryNativeMysqlTest extends RetailStockReplenishmentNativeFixture {
    private StockReplenishmentSourcePort source;
    private static BizException unavailable() { return BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE); }

    @BeforeEach void trustedSource() {
        source = mock(StockReplenishmentSourcePort.class);
        when(source.checkpoint(any(), any())).thenReturn(new Checkpoint("INV", "OWNER", 10));
        for (int i = 1; i <= 3; i++) when(source.outbound(S, "A", "OUT" + i, 1)).thenReturn(source("OUT" + i, 10 + i));
        beans.registerSingleton("source", source);
    }
    private void on() { effects.configure(S, "A", "on", new Change(true, 0, "start")); }

    @Test void fullRetailLoopUsesTrustedSourceOutsideTheLedgerAndReplaysOriginalKeys() {
        on();
        verify(source).checkpoint(S, "A");
        assertThat(effects.configure(S, "A", "on", new Change(true, 0, "start")).enabled()).isTrue();
        verify(source, times(1)).checkpoint(any(), any());

        var registered = effects.register(S, "A", "reg", "OUT1", 1, mapping());
        String no = registered.original().originalNo();
        assertThat(registered.original().target().quantityScope().name()).isEqualTo("ENTITY");
        assertThat(stock()).isEqualTo(17);
        doThrow(unavailable()).when(source).outbound(S, "A", "OUT1", 1);
        assertThat(effects.register(S, "A", "reg", "OUT1", 1, mapping()).original().originalNo()).isEqualTo(no);
        assertThat(effects.list(S, null, 20, true).items()).extracting(v -> v.original().originalNo()).containsExactly(no);

        effects.receive(S, "A", "r15", no, 15);
        var adjustment = effects.adjust(S, "A", "count", "SKU", "EA", 5, "arrived without scan");
        assertThat(stock()).isEqualTo(37);
        assertThat(effects.adjust(S, "A", "count", "SKU", "EA", 5, "arrived without scan")).isEqualTo(adjustment);
        assertThatThrownBy(() -> effects.adjust(S, "A", "count", "SKU", "EA", 5, "different")).isInstanceOf(BizException.class);
        effects.adoptManual(S, "A", "adopt", no, adjustment.movementNo());
        assertThat(stock()).isEqualTo(37);
        assertThat(effects.list(S, null, 20, true).items()).isEmpty();

        effects.dispatchReturn(S, "A", "back", no, 5, "damaged");
        assertThat(stock()).isEqualTo(32);
        assertThat(effects.list(S, null, 20, true).items()).hasSize(1);
        doReturn(new Inbound("INV", "OWNER", "BACK", 1, "SOURCE", "EA", new BigDecimal("5"), no))
                .when(source).inbound(S, "A", "BACK", 1);
        var acked = effects.ackReturn(S, "A", "ack", no, "BACK", 1);
        assertThat(acked.returnAcceptedQuantity()).isEqualByComparingTo("5");
        doThrow(unavailable()).when(source).inbound(S, "A", "BACK", 1);
        assertThat(effects.ackReturn(S, "A", "ack", no, "BACK", 1)).isEqualTo(acked);
        assertThat(stock()).isEqualTo(32);

        doReturn(source("OUT1", 11)).when(source).outbound(S, "A", "OUT1", 1);
        var reconciliation = effects.reconcile(S, "A", no);
        assertThat(reconciliation.issues()).isEmpty();
        assertThat(reconciliation.manualQuantity()).isEqualByComparingTo("5");
        assertThat(count("prd_stock_replenishment_movement")).isEqualTo(3);
    }

    @Test void foodStoresAndOtherStoresCannotUseTheRetailEntry() {
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("FOOD"));
        assertThatThrownBy(this::on).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_writer")).isZero();
        assertThat(effects.connection(S).enabled()).isFalse();
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("RETAIL"));
        on();
        String no = effects.register(S, "A", "reg", "OUT1", 1, mapping()).original().originalNo();
        var sibling = new Scope("MAIN", "E", "S2");
        assertThat(effects.list(sibling, null, 20, false).items()).isEmpty();
        assertThatThrownBy(() -> effects.get(sibling, no)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> effects.receive(sibling, "A", "r", no, 1)).isInstanceOf(BizException.class);
        assertThat(stock()).isEqualTo(17);
    }

    @Test void listPagesByOriginalNumberWithoutDatabaseIds() {
        on();
        for (int i = 1; i <= 3; i++) effects.register(S, "A", "reg" + i, "OUT" + i, 1, mapping());
        var first = effects.list(S, null, 2, false);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isEqualTo(first.items().getLast().original().originalNo());
        var rest = effects.list(S, first.nextCursor(), 2, false);
        assertThat(rest.items()).extracting(v -> v.original().source().documentNo()).containsExactly("OUT3");
        assertThat(rest.nextCursor()).isNull();
    }

    @Test void closedConnectionStopsNewSourcesButOriginalsFinishAndMissingSourceIsUnverified() {
        on();
        String no = effects.register(S, "A", "reg", "OUT1", 1, mapping()).original().originalNo();
        effects.configure(S, "A", "off", new Change(false, effects.connection(S).revision(), "pause"));
        assertThatThrownBy(() -> effects.register(S, "A", "reg2", "OUT2", 1, mapping())).isInstanceOf(BizException.class);
        effects.receive(S, "A", "r", no, 20);
        assertThat(stock()).isEqualTo(37);
        beans.destroySingleton("source");
        assertThat(effects.reconcile(S, "A", no).issues()).contains("SOURCE_UNVERIFIED");
        assertThatThrownBy(() -> effects.register(S, "A", "reg3", "OUT3", 1, mapping())).isInstanceOf(BizException.class);
    }
}
