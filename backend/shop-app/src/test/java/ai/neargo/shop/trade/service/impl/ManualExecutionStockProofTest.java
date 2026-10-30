package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort.CurrentState;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.Outcome;
import ai.neargo.shop.spi.product.StockDispositionPort.OwnerLine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks consumption of complete original facts; no owner command or database is simulated here. */
class ManualExecutionStockProofTest {
    private final List<Need> needs = List.of(new Need("SKU", "STORE", "CHILD", 2));
    private final Original original = new Original("MAIN", "ENTITY", "ORDER", "PLATFORM", 1,
            StockDispositionContract.manifestHash(needs), needs);
    private final String operation = StockDispositionContract.operationNo(original);

    @Test
    void onlyCompleteAllocatedOrConsumedOriginalCanBackPreparation() {
        assertDoesNotThrow(() -> check(Outcome.APPLIED, CurrentState.ALLOCATED,
                List.of(line("ROW", "SKU", 2, 0, 0, CurrentState.ALLOCATED))));
        assertDoesNotThrow(() -> check(Outcome.APPLIED, CurrentState.CONSUMED,
                List.of(line("ROW", "SKU", 0, 2, 0, CurrentState.CONSUMED))));
    }

    @Test
    void partialReleaseOrMixedDispositionCannotBackPreparation() {
        assertThrows(BizException.class, () -> check(Outcome.APPLIED, CurrentState.ALLOCATED,
                List.of(line("ROW", "SKU", 1, 0, 1, CurrentState.ALLOCATED))));
        assertThrows(BizException.class, () -> check(Outcome.APPLIED, CurrentState.ALLOCATED,
                List.of(line("ROW", "SKU", 0, 2, 0, CurrentState.CONSUMED))));
    }

    @Test
    void pendingAndDuplicateOwnerRowsCannotManufactureAnAppliedManifest() {
        var row = line("ROW", "SKU", 2, 0, 0, CurrentState.ALLOCATED);
        assertEquals(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                assertThrows(BizException.class, () -> check(Outcome.PENDING, CurrentState.ALLOCATED, List.of(row)))
                        .errorCode());
        assertEquals(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                assertThrows(BizException.class, () -> check(Outcome.UNKNOWN, CurrentState.MISSING, List.of()))
                        .errorCode());
        assertThrows(BizException.class, () -> check(Outcome.APPLIED, CurrentState.ALLOCATED, List.of(row, row)));
    }

    @Test
    void changedSkuOrEmptyOwnerLinesCannotSatisfyFrozenManifest() {
        assertThrows(BizException.class, () -> check(Outcome.APPLIED, CurrentState.ALLOCATED,
                List.of(line("ROW", "OTHER", 2, 0, 0, CurrentState.ALLOCATED))));
        assertThrows(BizException.class, () -> check(Outcome.APPLIED, CurrentState.ALLOCATED, List.of()));
    }

    @Test
    void sourceMutationCannotRunWithoutTheOwnersTransaction() {
        assertThrows(IllegalStateException.class, ServiceExecutionServiceImpl::requireTransaction);
    }

    private void check(Outcome outcome, CurrentState state, List<OwnerLine> rows) {
        ServiceExecutionServiceImpl.requireStock(original, operation,
                new Disposition(original, operation, outcome, state, rows, "a".repeat(64), Instant.EPOCH, null));
    }

    private static OwnerLine line(String id, String sku, int allocated, int consumed, int released,
            CurrentState state) {
        return new OwnerLine(id, sku, "STORE", "CHILD", "OWNER", "ITEM", "LOCATION", false,
                2, allocated, consumed, released, 1, state);
    }
}
