package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.port.FoodSourceValidationProviderImpl;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.PublicationMarketPolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FoodSourceValidationTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final FoodCatalogBindingFixtures.MutableClock clock = new FoodCatalogBindingFixtures.MutableClock();
    final ConfigRevisionService menus = mock(ConfigRevisionService.class);
    final Source source = new Source("E", "S", Owner.FOOD, Kind.MENU, "M", "MR");
    final FoodSourceValidationProviderImpl provider = new FoodSourceValidationProviderImpl(() -> menus, f.json, clock);

    void wire(Proof proof) {
        when(menus.resolveRevision(anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return proof.publication();
        });
        when(menus.verifyMenuResource(FoodCatalogBindingFixtures.QUERY.menu())).thenReturn(proof);
    }

    @Test void validatesOriginalMenuAndPreservesOriginalDeadlineHashAndReceipt() {
        var proof = f.menu(); wire(proof);
        var result = provider.validate(source);
        assertThat(result.status()).isEqualTo(Status.VALID);
        assertThat(result.canonicalRef()).isEqualTo("M");
        assertThat(result.canonicalRevision()).isEqualTo("MR");
        assertThat(result.contentHash()).isEqualTo(proof.publication().publication().contentHash());
        assertThat(result.ownerReceiptNo()).isEqualTo(proof.receiptNo());
        assertThat(result.validUntil()).isEqualTo(proof.validUntil().toInstant());
    }

    @Test void acceptsMarketSchemaSixWithoutReinterpretingFrozenCurrency() {
        var old = f.menu();
        var body = f.json.readValue(old.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var scope = new MarketScope("E", "S");
        var facts = CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", scope,
                new Market("AE", "AED", 2), clock.instant, clock.instant.plusSeconds(30), null));
        var context = PublicationMarketPolicy.capture(scope, facts, clock.instant);
        var document = PublicationDocuments.freeze(f.json, new MenuPublicationSnapshot(6, "MENU", "M", 0,
                body.menu(), body.skuFacts(), body.goodsFacts(), null, context));
        var meta = old.publication().publication();
        var content = new ConfigRevisionContentVO(new ConfigRevisionVO("MR", "MENU", "M", 1,
                meta.effectiveAt(), PublicationDocuments.hash(f.json, document), "EFFECTIVE"), document);
        var unsigned = new Proof(1, old.query(), content, MenuSourcePolicy.project(f.json, old.query(), content),
                old.verifiedAt(), old.validUntil(), null);
        var proof = new Proof(1, old.query(), content, unsigned.resource(), unsigned.verifiedAt(),
                unsigned.validUntil(), MenuSourcePolicy.receipt(f.json, unsigned));
        wire(proof);
        assertThat(provider.validate(source).contentHash()).isEqualTo(content.publication().contentHash());
    }

    @Test void malformedScopeAndOverlongMenuIdentityFailBeforeOriginalRead() {
        assertThatThrownBy(() -> provider.validate(new Source("E\n", "S", Owner.FOOD, Kind.MENU, "M", "MR")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> provider.validate(new Source("E", "S", Owner.FOOD, Kind.MENU, "M".repeat(33), "MR")))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(menus);
    }

    @Test void explicitDifferentCurrentRevisionIsStaleWithoutGrantingAnyProof() {
        var old = f.menu().publication(); var p = old.publication();
        when(menus.resolveRevision(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(
                new ConfigRevisionContentVO(new ConfigRevisionVO("NEW", "MENU", "M", 2, p.effectiveAt(),
                        p.contentHash(), "EFFECTIVE"), old.definitionSnapshot()));
        assertThat(provider.validate(source).status()).isEqualTo(Status.STALE);
    }

    @Test void unsupportedKindsAndSalesOfferCannotMasqueradeAsMenu() {
        for (var input : List.of(new Source("E", "S", Owner.FOOD, Kind.SKU, "M", "MR"),
                new Source("E", "S", Owner.RETAIL, Kind.MENU, "M", "MR"),
                new Source("E", "S", Owner.FOOD, Kind.MENU, "M", "MR", "OFFER"))) {
            assertThat(provider.validate(input).status()).isEqualTo(Status.UNSUPPORTED);
        }
        verifyNoInteractions(menus);
    }

    @Test void expiredAndWrongScopeOriginalProofsAreNotAccepted() {
        wire(f.menu()); clock.instant = FoodCatalogBindingFixtures.NOW.plusSeconds(30);
        assertThatThrownBy(() -> provider.validate(source)).isInstanceOf(IllegalStateException.class);
        clock.instant = FoodCatalogBindingFixtures.NOW;
        assertThatThrownBy(() -> provider.validate(new Source("E", "OTHER", Owner.FOOD, Kind.MENU, "M", "MR")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void notFoundPermissionTransportAndUnconfirmedConflictRetainOriginalMeaning() {
        for (var failure : List.of(BizException.of(ErrorCode.NOT_FOUND), BizException.of(ErrorCode.FORBIDDEN),
                BizException.of(ErrorCode.CONFLICT), new IllegalStateException("transport"))) {
            doThrow(failure).when(menus).resolveRevision(anyString(), anyString(), anyString(), anyString(), any());
            assertThatThrownBy(() -> provider.validate(source)).isSameAs(failure);
        }
    }

    @Test void changingOriginalContentAtTheSameRevisionCannotMixHashAndReceipt() {
        wire(f.menu());
        when(menus.verifyMenuResource(FoodCatalogBindingFixtures.QUERY.menu())).thenReturn(
                f.menu("Changed", f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"), true, 25));
        assertThatThrownBy(() -> provider.validate(source)).isInstanceOf(BizException.class);
    }
}
