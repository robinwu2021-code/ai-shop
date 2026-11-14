package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Withdrawal;
import ai.neargo.shop.trade.entity.OrdChannelImport;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real primary guard transactions; withdrawal authority is an isolated input contract, not a native
 * end-to-end claim.
 */
class IndustryChannelAdmissionGuardTest {
    IndustryChannelStockLifecycleTest world;

    @BeforeEach
    void setup() throws Exception {
        world = new IndustryChannelStockLifecycleTest();
        world.setup();
    }

    Withdrawal proof() {
        return new Withdrawal(
                world.ref,
                "PLATFORM_CANCELLED",
                "ACTUAL_ORIGINAL_WITHDRAWAL",
                world.now,
                world.now.plusSeconds(25),
                "ACTUAL_SOURCE_READ");
    }

    @Test
    void cancellationBeforeAdmissionCreatesARealTombstoneWithoutInventingTradeOrQuoteIdentities() {
        var store = world.store;
        store.atomic(
                () -> {
                    store.imports.delete(
                            Wrappers.<OrdChannelImport>query().eq("import_ref", "IMPORT"));
                    return null;
                });
        var cancellation = store.beginCancellation(world.ref, proof());
        assertThat(cancellation).isNotNull();
        assertThat(cancellation.reference()).isEqualTo(world.ref);
        assertThat(cancellation.cancellationReceiptRef()).isNotBlank();
        assertThat(store.query(() -> store.guard(world.ref, false).getStatus()))
                .isEqualTo("CANCELLED");
        assertThat(store.beginCancellation(world.ref, proof())).isEqualTo(cancellation);
        assertThatThrownBy(
                        () ->
                                store.atomic(
                                        () -> {
                                            store.activeGuard(world.ref);
                                            return null;
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.query(() -> store.find(world.ref, false))).isNull();
        verify(world.inventory, never()).reserve(any(), anyLong(), anyInt());
    }

    @Test
    void existingPreparationRequiresRealStockWithdrawalBeforeTheGuardCanProduceATerminalReceipt() {
        var store = world.store;
        assertThat(store.beginCancellation(world.ref, proof())).isNull();
        var guard = store.query(() -> store.guard(world.ref, false));
        assertThat(guard.getStatus()).isEqualTo("CANCELLING");
        assertThat(guard.getCancellationReceiptRef()).isNull();
        assertThatThrownBy(() -> world.service.prepare(world.ref, world.source))
                .isInstanceOf(RuntimeException.class);
        verify(world.inventory, never()).reserve(any(), anyLong(), anyInt());
        assertThat(store.query(() -> store.required(world.ref, false).getStatus()))
                .isEqualTo("WAITING_STOCK");
    }

    @Test
    void anAcceptedBusinessCannotBeCancelledThroughAPreparationGuard() {
        var store = world.store;
        store.atomic(
                () -> {
                    store.imports.update(
                            Wrappers.<OrdChannelImport>update()
                                    .eq("import_ref", "IMPORT")
                                    .set("status", "ACCEPTED"));
                    return null;
                });
        assertThatThrownBy(() -> store.beginCancellation(world.ref, proof()))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.query(() -> store.guard(world.ref, false).getStatus()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void anotherSourceHashOrVersionCannotBorrowAnExistingGuard() {
        var reference = world.ref;
        for (var changed :
                new Reference[] {
                    new Reference(
                            reference.scope(),
                            reference.sourceSystem(),
                            reference.sourceNo(),
                            2,
                            reference.sourceHash(),
                            reference.importRef()),
                    new Reference(
                            reference.scope(),
                            reference.sourceSystem(),
                            reference.sourceNo(),
                            1,
                            "b".repeat(64),
                            reference.importRef())
                })
            assertThatThrownBy(() -> world.store.query(() -> world.store.guard(changed, false)))
                    .isInstanceOf(RuntimeException.class);
    }
}
