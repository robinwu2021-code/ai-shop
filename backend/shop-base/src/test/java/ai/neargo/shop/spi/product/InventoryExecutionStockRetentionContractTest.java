package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentDescriptor;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ProofMode;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Full native descriptors keep parent request, prepared revision, source identity and confirmation inseparable. */
class InventoryExecutionStockRetentionContractTest {
    private static final String HASH = "a".repeat(64);

    private ExecutionReference reference(String payload) {
        return new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1", new ExecutionScope("MAIN", "ENTITY", "STORE"),
                ExecutionReference.SourceKind.STORE_ORDER, "MANUAL_EXECUTION_V1", "EXEC", "GRANT", "UNIT", "TASK",
                "WORK", 1, 1, HASH, payload, HASH, HASH, HASH, "FENCE", 1, HASH, "BTRC", HASH, "PREPARE", HASH,
                HASH, null).signed();
    }

    private ComponentDescriptor nativeComponent(String payload) {
        var needs = List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 2));
        var original = new StockDispositionPort.Original("MAIN", "ENTITY", "ORDER", "INVENTORY", 2,
                StockDispositionContract.manifestHash(needs), needs);
        var lookup = ExecutionStockRetentionContract.request(reference(payload), original,
                StockDispositionContract.operationNo(original)).lookup();
        return InventoryExecutionStockRetentionContract.nativeComponent(lookup, "CONFIRMATION");
    }

    @Test
    void nativeComponentBindsTheFullReferenceAndPreservesParentManifest() {
        var component = nativeComponent(HASH);
        component.validate();
        assertThat(component.proofMode()).isEqualTo(ProofMode.NATIVE_CONFIRMATION);
        assertThat(component.expectedNeeds()).isEqualTo(component.original().stockOriginal().needs());
        assertThat(component.componentManifestHash()).isEqualTo(component.parentManifestHash());
        assertThat(nativeComponent("b".repeat(64)).componentNo()).isNotEqualTo(component.componentNo());
        assertThat(nativeComponent(HASH)).isEqualTo(component);
    }

    @Test
    void shortenedOrReinterpretedDescriptorCannotReuseACompleteOriginalDigest() {
        var component = nativeComponent(HASH);
        var shortened = new ComponentDescriptor(component.schema(), component.componentNo(), component.original(),
                component.proofMode(), List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 1)),
                component.parentManifestHash(), component.componentManifestHash(), component.sourceConfirmationNo(),
                component.componentHash());
        assertThatThrownBy(shortened::validate).isInstanceOf(IllegalArgumentException.class);
        var forged = new ComponentDescriptor(component.schema(), component.componentNo(), component.original(),
                ProofMode.DUAL_MIRROR, component.expectedNeeds(), component.parentManifestHash(),
                component.componentManifestHash(), component.sourceConfirmationNo(), component.componentHash());
        assertThatThrownBy(forged::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
