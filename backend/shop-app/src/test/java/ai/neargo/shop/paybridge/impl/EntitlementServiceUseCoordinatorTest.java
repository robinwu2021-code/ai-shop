package ai.neargo.shop.paybridge.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntitlementServiceUseCoordinatorTest {
    private final EntitlementServiceOrderService orders = mock(EntitlementServiceOrderService.class);
    private final EntitlementUsePort pay = mock(EntitlementUsePort.class);
    private final EntitlementUseFactsPort facts = mock(EntitlementUseFactsPort.class);
    private final EntitlementServiceUseCoordinatorImpl service = new EntitlementServiceUseCoordinatorImpl(
            orders, mock(EntitlementQueryPort.class), pay, facts);
    private final EntitlementContext context = new EntitlementContext("MAIN", "actor", "request", "a".repeat(64));
    private final SourceRef source = new SourceRef("CORE", "SERVICE_USE", "order", null, null, null, 0);
    private final List<UseItem> items = List.of(new UseItem("line", "goods", "Service", 1, 100));
    private final UseCommand command = new UseCommand("operation", source, "member", "entity", "store",
            "CNY", AssetKind.STORED_VALUE, null, "wallet", null, null, 100L, items, "auth",
            null, 0, null, null);
    private final String snapshotHash = "b".repeat(64);
    private final Instant at = Instant.parse("2026-11-11T00:00:00Z");

    private void original(String state) {
        var fact = new UseSourceFact("MAIN", source, "member", "entity", "store", "CNY",
                items, snapshotHash, "Store", null, null);
        when(orders.findOperation("MAIN", "entity", "operation")).thenReturn(
                new ServiceOrderSnapshot("order", "operation", state, fact, context, null, command,
                        null, at, null));
    }

    @Test
    void recoveryCannotAcceptFactsForAnotherOperation() {
        original("PENDING");
        when(pay.prepare(context, command)).thenReturn(new HoldView("operation", State.HELD, "hold", null, at));
        var other = new HoldView("other", State.COMMITTED, "other-hold", "other-usage", at);
        when(facts.query(context, "entity", "operation")).thenReturn(new UseFact(other, command, null,
                List.of(), snapshotHash));

        assertThat(service.recover(context, "entity", "operation").status()).isEqualTo(State.PROCESSING);
        verify(orders, never()).accept(any(), anyString(), any());
        verify(orders, never()).acknowledge(any(), anyString(), any());
    }

    @Test
    void originalReceiptIsVisibleOnlyWithMatchingFrozenSource() {
        original("COMMITTED");
        var receipt = new HoldView("operation", State.COMMITTED, "hold", "usage", at);
        when(facts.query(context, "entity", "operation"))
                .thenReturn(new UseFact(receipt, command, null, List.of(), "c".repeat(64)),
                        new UseFact(receipt, command, null, List.of(), snapshotHash));

        assertThat(service.query(context, "entity", "operation").status()).isEqualTo(State.PROCESSING);
        assertThat(service.query(context, "entity", "operation")).isEqualTo(receipt);
    }
}
