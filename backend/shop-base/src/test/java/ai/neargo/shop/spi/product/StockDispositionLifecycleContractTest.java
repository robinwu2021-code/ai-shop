package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Credit originals use native Inventory semantics without reinterpreting platform/dual legacy versions. */
class StockDispositionLifecycleContractTest {
    private static final List<Need> NEEDS = List.of(new Need("SKU", "STORE", "FULFILLMENT", 2));

    private Original original(String authority, int lifecycle) {
        return new Original("MAIN", "ENTITY", "ORDER", authority, lifecycle,
                StockDispositionContract.manifestHash(NEEDS), NEEDS);
    }

    @Test
    void nativeInventoryCreditOriginalKeepsTheFrozenManifestAndOperationIdentity() {
        var credit = original("INVENTORY", 3);
        assertThatCode(() -> StockDispositionContract.validate(credit)).doesNotThrowAnyException();
        assertThat(credit.manifestHash()).isEqualTo(original("INVENTORY", 2).manifestHash());
        assertThat(StockDispositionContract.operationNo(credit))
                .isNotEqualTo(StockDispositionContract.operationNo(original("INVENTORY", 2)));
        assertThat(StockDispositionContract.reservationOperationNo(credit))
                .isNotEqualTo(StockDispositionContract.reservationOperationNo(original("INVENTORY", 2)));
        assertThatThrownBy(() -> StockDispositionContract.requireOperation(credit,
                StockDispositionContract.operationNo(original("INVENTORY", 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void existingVersionsRemainValidButCreditCannotUseAnUnsupportedOwner() {
        for (String authority : List.of("PLATFORM", "DUAL", "INVENTORY")) {
            for (int version : List.of(1, 2)) {
                assertThatCode(() -> StockDispositionContract.validate(original(authority, version)))
                        .doesNotThrowAnyException();
            }
            if (!"INVENTORY".equals(authority)) {
                assertThatThrownBy(() -> original(authority, 3)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> original(authority, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> original(authority, 4)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void creditStillRejectsForgedManifestAndInvalidFrozenCoordinates() {
        assertThatThrownBy(() -> new Original("MAIN", "ENTITY", "ORDER", "INVENTORY", 3,
                "a".repeat(64), NEEDS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Original("OTHER", "ENTITY", "ORDER", "INVENTORY", 3,
                StockDispositionContract.manifestHash(NEEDS), NEEDS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Original("MAIN", "", "ORDER", "INVENTORY", 3,
                StockDispositionContract.manifestHash(NEEDS), NEEDS)).isInstanceOf(IllegalArgumentException.class);
    }
}
