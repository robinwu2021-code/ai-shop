package ai.neargo.shop.product.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.entity.PrdExecutionRetention;
import ai.neargo.shop.product.mapper.ProductMappers.ExecutionRetentionMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local read-only fence: stock roots precede links, claims and balances; no cross-owner IO. */
@Component
public final class ExecutionStockRetentionGuard {
    private final ExecutionRetentionMapper links;
    private final ExecutionStockRetentionSchemaVerifier schema;
    public ExecutionStockRetentionGuard(ExecutionRetentionMapper links, ExecutionStockRetentionSchemaVerifier schema) {
        this.links = links;
        this.schema = schema;
    }

    /** Legacy retail bypass is legal only for a verifiably absent installation, never physical drift. */
    public List<PrdExecutionRetention> lockedLinks(List<PrdStockLock> originals) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException(
            "stock roots required");
        var state = schema.installation();
        if (state == ExecutionStockRetentionSchemaVerifier.Installation.ABSENT) return List.of();
        if (state != ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                "EXECUTION_STOCK_RETENTION_SCHEMA_UNAVAILABLE");
        }
        if (originals.isEmpty()) return List.of();
        return DataScopeContext.executeWithoutScope(() -> links.lockOriginalLinks(
                originals.stream().map(PrdStockLock::getId).toList()));
    }

    /** Check the complete affected set before touching a marker, a claim or any balance. */
    public void checkLocked(List<PrdStockLock> originals) {
        if (originals.stream().anyMatch(row -> !Integer.valueOf(0).equals(row.getDeleted()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        for (var row : lockedLinks(originals)) {
            if (!Integer.valueOf(0).equals(row.getDeleted()) || !"RELEASED_NO_EFFECT".equals(row.getState())
                    || row.getNoEffectSealHash() == null) throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
