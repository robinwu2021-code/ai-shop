package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import java.util.List;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RowProof;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;

/** Deterministic permanent retention numbers bind complete originals, independent of current adapter routing. */
public final class ExecutionStockRetentionContract {
    private ExecutionStockRetentionContract() {
    }

    public static String originalHash(StockDispositionPort.Original original) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_ORIGINAL_V1", original);
    }

    public static String requestHash(ExecutionReference ref, StockDispositionPort.Original original, String operation) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_RETAIN_REQUEST_V1", List.of(ref, original, operation));
    }

    public static String retentionNo(ExecutionReference ref, StockDispositionPort.Original original, String operation) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_RETENTION_NO_V1", List.of(ref, original, operation));
    }

    public static RetainExecutionStock request(ExecutionReference ref, StockDispositionPort.Original original,
            String operation) {
        return new RetainExecutionStock(ref, original, operation, retentionNo(ref, original, operation),
                requestHash(ref, original, operation));
    }

    public static void validate(ExecutionReference ref, StockDispositionPort.Original original, String operation,
            String retentionNo, String requestHash) {
        ref.validate();
        StockDispositionContract.requireOperation(original, operation);
        // Permanent execution evidence has no lifecycle3 acceptance/payment observer yet.
        // A valid credit return original must not implicitly opt into the V1 retention protocol.
        require((original.lifecycleVersion() == 1 || original.lifecycleVersion() == 2)
                && ref.scope().tenantNo().equals(original.tenantNo()) && ref.scope().entityNo().equals(original.entityNo())
                && !original.needs().isEmpty()
                && original.needs().stream().allMatch(n -> ref.scope().storeNo().equals(n.sourceStoreNo()))
                && retentionNo(ref, original, operation).equals(retentionNo)
                && requestHash(ref, original, operation).equals(requestHash));
    }

    /** Independently observed consumed identity excludes mutable link revision, retaining every stock/restore fact. */
    public static List<RowProof> issueRows(List<RowProof> rows) {
        require(!rows.isEmpty() && rows.stream().allMatch(row -> "CONSUMED".equals(row.disposition())
                && row.restoreClaims().isEmpty()));
        return rows.stream().map(p -> new RowProof(p.originalRowId(), p.lockNo(), p.skuNo(), p.sourceStoreNo(),
                p.effectiveStoreNo(), p.fulfillmentRef(), p.lifecycleVersion(), p.presale(), p.originalQuantity(),
                p.originalManifestHash(), p.confirmationOperationNo(), p.retainedOriginalVersion(),
                p.currentOriginalVersion(), p.disposition(), 0, p.restoreClaims()))
                .sorted(java.util.Comparator.comparingLong(RowProof::originalRowId)).toList();
    }

    public static String issueObservationHash(RetentionLookup original, List<RowProof> rows) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_ISSUE_OBSERVATION_V1", List.of(original,
            issueRows(rows)));
    }

    public static void require(boolean valid) {
        ExecutionEvidenceContract.require(valid);
    }
}
