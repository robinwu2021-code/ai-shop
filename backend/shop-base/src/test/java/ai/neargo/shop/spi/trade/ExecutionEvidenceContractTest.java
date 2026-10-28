package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** New neutral identities preserve exact old bytes and bind all original coordinates. */
class ExecutionEvidenceContractTest {
    private static final String HASH = "a".repeat(64);
    private static ExecutionReference reference(String payloadHash) {
        return new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1",
                new ServiceExecutionPort.ExecutionScope("MAIN", "ENTITY", "STORE"),
                ExecutionReference.SourceKind.STORE_ORDER, "MANUAL_EXECUTION_V1", "EXEC", "GRANT", "UNIT",
                "TASK", "WORK", 1, 1, HASH, payloadHash, HASH, HASH, HASH, "FENCE", 1, HASH, "BTRC", HASH,
                "PREPARE", HASH, HASH, null).signed();
    }

    @Test
    void exactStoredBytesAreNotReplacedByReserializedJson() {
        assertThat(ExecutionEvidenceContract.bindingBytesHash("{\"a\":1}"))
                .isNotEqualTo(ExecutionEvidenceContract.bindingBytesHash("{ \"a\":1 }"));
    }

    @Test
    void payloadChangeWithUnchangedLegacyBindingHashChangesTheNeutralOriginal() {
        var original = reference(HASH);
        var changed = reference("b".repeat(64));
        assertThat(changed.bindingHash()).isEqualTo(original.bindingHash());
        assertThat(changed.referenceHash()).isNotEqualTo(original.referenceHash());
    }

    @Test
    void retentionIdentityBindsTheCompleteFrozenOriginalAndConfirmationOperation() {
        var needs = List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 2));
        var original = new StockDispositionPort.Original("MAIN", "ENTITY", "ORDER", "PLATFORM", 2,
                StockDispositionContract.manifestHash(needs), needs);
        String operation = StockDispositionContract.operationNo(original);
        var first = ExecutionStockRetentionContract.request(reference(HASH), original, operation);
        assertThat(ExecutionStockRetentionContract.request(reference(HASH), original, operation)).isEqualTo(first);
        assertThat(ExecutionStockRetentionContract.request(reference("b".repeat(64)), original,
            operation).retentionNo())
                .isNotEqualTo(first.retentionNo());
        assertThatThrownBy(() -> ExecutionStockRetentionContract.request(reference(HASH), original, "OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockDispositionPort.Original("MAIN", "ENTITY", "ORDER", "PLATFORM", 2,
                HASH, needs)).isInstanceOf(IllegalArgumentException.class);
    }
}
