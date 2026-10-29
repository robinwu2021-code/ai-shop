package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.product.RetailProductSourcePort;

import org.junit.jupiter.api.Test;

import java.util.List;

class RetailChannelSourceReaderTest {
    @Test
    void
            expiredOriginalPublicationKeepsInvoiceAndUsesOnlyActualHistoricalMembershipAndMaterialUnit() {
        var f = new RetailChannelOrderFixtures();
        var result = f.reader.original(f.reference);
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().unit()).isEqualTo("PIECE");
        assertThat(result.lines().getFirst().invoice().grossExTaxMinor()).isEqualTo(190);
        assertThat(result.lines().getFirst().variant().price().amountMinor()).isEqualTo(100);
        assertThat(result.publication().originalPublicationReceiptRef()).isEqualTo("ITEMS_RECEIPT");
        assertThat(result.productProofs().getFirst().query().historical()).isTrue();
        assertThat(result.validUntil()).isEqualTo(f.now.plusSeconds(18));
        verify(f.selections, never()).current(any(), any(), any());
    }

    @Test
    void currentReadDoesNotReloadPublishedCatalogProductHistoryOrMaterialFacts() {
        var f = new RetailChannelOrderFixtures();
        assertThat(f.reader.current(f.reference).attemptRef()).isEqualTo("QUERY_ATTEMPT");
        verify(f.evidence, never()).publishedCatalog(any());
        verifyNoInteractions(f.selections, f.products, f.materials);
    }

    @Test
    void
            missingPhysicalDefinitionAndChangedHistoricalProductCannotBecomeNoStockOrAReplacementSku() {
        var f = new RetailChannelOrderFixtures();
        when(f.materials.getSkuMaterials("E", "S", List.of("SKU"))).thenReturn(List.of());
        assertThatThrownBy(() -> f.reader.original(f.reference))
                .isInstanceOf(IllegalStateException.class);
        var other = new RetailChannelOrderFixtures();
        when(other.products.read(any()))
                .thenAnswer(
                        a ->
                                new RetailProductSourcePort.Facts(
                                        a.getArgument(0),
                                        7,
                                        "c".repeat(64),
                                        List.of("SKU"),
                                        other.now,
                                        other.now.plusSeconds(20),
                                        "WRONG_ORIGINAL"));
        assertThatThrownBy(() -> other.reader.original(other.reference))
                .isInstanceOf(IllegalStateException.class);
    }
}
