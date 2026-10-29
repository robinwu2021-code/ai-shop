package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.retail.commerce.port.RetailSourceValidationProviderImpl;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.product.RetailProductSourcePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class RetailSourceValidationProviderTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final RetailSelectionMapper repository = mock(RetailSelectionMapper.class);
    final RetailProductSourcePort products = mock(RetailProductSourcePort.class);
    final RetailSourceValidationProviderImpl provider =
            new RetailSourceValidationProviderImpl(repository, products, clock);
    final RetailSelection selection =
            new RetailSelection(
                    "E",
                    "S",
                    "SEL",
                    "R1",
                    "C",
                    "CR",
                    List.of(new RetailSelection.Entry("G", 7, "a".repeat(64), List.of("SKU"))));

    @BeforeEach
    void original() {
        when(repository.revision("E", "S", "SEL", "R1")).thenReturn(selection);
        when(repository.current("E", "S", "SEL")).thenReturn(selection);
        when(products.read(any()))
                .thenAnswer(
                        call -> facts(call.getArgument(0), "a".repeat(64), now.plusSeconds(20)));
    }

    RetailProductSourcePort.Facts facts(
            RetailProductSourcePort.Query q, String hash, Instant until) {
        return new RetailProductSourcePort.Facts(q, 7, hash, List.of("SKU"), now, until, "OWNER");
    }

    Source root() {
        return new Source("E", "S", Owner.RETAIL, Kind.SELECTION, "SEL", "R1");
    }

    Source sku(ReadMode mode, String ref, String offer) {
        return new Source(
                "E",
                "S",
                Owner.RETAIL,
                Kind.SKU,
                ref,
                "R1",
                offer,
                new SourceContext("SEL", null, mode));
    }

    @Test
    void currentRootUsesOriginalPublishedHashAndShorterDeadline() {
        var proof = provider.validate(root());
        assertThat(proof.status()).isEqualTo(Status.VALID);
        assertThat(proof.contentHash()).isEqualTo(selection.contentHash());
        assertThat(proof.validUntil()).isEqualTo(now.plusSeconds(20));
        verify(products).read(new RetailProductSourcePort.Query("E", "S", "G", 7, false));
    }

    @Test
    void historicalSkuProvesMembershipWithoutGrantingCurrentSale() {
        clearInvocations(repository);
        var proof = provider.validate(sku(ReadMode.HISTORICAL, "SKU", "G"));
        assertThat(proof.status()).isEqualTo(Status.VALID);
        assertThat(proof.canonicalSalesOfferRef()).isEqualTo("G");
        verify(repository, never()).current(any(), any(), any());
        verify(products).read(new RetailProductSourcePort.Query("E", "S", "G", 7, true));
    }

    @Test
    void wrongOfferAndUnknownSkuNeverAcquireMembership() {
        assertThat(provider.validate(sku(ReadMode.HISTORICAL, "SKU", "OTHER")).status())
                .isEqualTo(Status.UNKNOWN);
        assertThat(provider.validate(sku(ReadMode.HISTORICAL, "MISSING", "G")).status())
                .isEqualTo(Status.UNKNOWN);
        verifyNoInteractions(products);
    }

    @Test
    void bareSkuCannotGuessSelectionParent() {
        assertThat(
                        provider.validate(new Source("E", "S", Owner.RETAIL, Kind.SKU, "SKU", "R1"))
                                .status())
                .isEqualTo(Status.UNSUPPORTED);
        verifyNoInteractions(products);
    }

    @Test
    void retailDoesNotFallbackToFoodOrInventCategoriesOrStock() {
        for (var kind :
                List.of(
                        Kind.MENU,
                        Kind.CATEGORY,
                        Kind.OPTION,
                        Kind.INVENTORY_POLICY,
                        Kind.WAREHOUSE))
            assertThat(
                            provider.validate(new Source("E", "S", Owner.RETAIL, kind, "SEL", "R1"))
                                    .status())
                    .isEqualTo(Status.UNSUPPORTED);
        assertThat(
                        provider.validate(new Source("E", "S", Owner.FOOD, Kind.MENU, "SEL", "R1"))
                                .status())
                .isEqualTo(Status.UNSUPPORTED);
        verifyNoInteractions(products);
    }

    @Test
    void oldSelectionCannotGrantCurrentSale() {
        when(repository.current("E", "S", "SEL")).thenReturn(null);
        assertThat(provider.validate(root()).status()).isEqualTo(Status.STALE);
        verifyNoInteractions(products);
    }

    @Test
    void changedOriginalPayloadCannotValidateFrozenSelection() {
        when(products.read(any()))
                .thenAnswer(c -> facts(c.getArgument(0), "b".repeat(64), now.plusSeconds(20)));
        assertThat(provider.validate(root()).status()).isEqualTo(Status.STALE);
    }

    @Test
    void expiredOwnerEvidenceIsRejected() {
        when(products.read(any())).thenAnswer(c -> facts(c.getArgument(0), "a".repeat(64), now));
        assertThatThrownBy(() -> provider.validate(root()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sourceCurrentResolvesOriginalHeadInsteadOfTrustingRequestedRevision() {
        var result =
                provider.current(new Source("E", "S", Owner.RETAIL, Kind.SELECTION, "SEL", "OLD"));
        assertThat(result.status()).isEqualTo(Status.VALID);
        assertThat(result.canonicalRevision()).isEqualTo("R1");
    }

    @Test
    void publicationRaceAfterOwnerReadIsRejected() {
        when(repository.current("E", "S", "SEL")).thenReturn(selection, null);
        assertThat(provider.validate(root()).status()).isEqualTo(Status.STALE);
    }

    @Test
    void ownerReadsNeverJoinCallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> provider.validate(root()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(products);
    }

    @Test
    void duplicateSkuCannotBePublishedAcrossProducts() {
        var invalid =
                new RetailSelection(
                        "E",
                        "S",
                        "SEL",
                        "R",
                        "C",
                        "CR",
                        List.of(
                                new RetailSelection.Entry("G", 1, "a".repeat(64), List.of("SKU")),
                                new RetailSelection.Entry(
                                        "G2", 2, "b".repeat(64), List.of("SKU"))));
        assertThatThrownBy(invalid::contentHash).isInstanceOf(IllegalArgumentException.class);
    }
}
