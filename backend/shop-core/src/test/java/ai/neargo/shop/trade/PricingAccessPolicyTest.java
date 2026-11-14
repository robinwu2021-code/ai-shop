package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Evidence;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.policy.PricingAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PricingAccessPolicyTest {
    private final StoreDirectoryPort stores = mock(StoreDirectoryPort.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final PricingAccessPolicy policy = new PricingAccessPolicy(stores,
            beans.getBeanProvider(PricingConfigEvidencePort.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        BizContext.clear();
    }

    @Test
    void draftsNeverReceiveLiveCapabilitiesOrLoadPublicationEvidence() {
        var provider = mock(PricingConfigEvidencePort.class);
        beans.registerSingleton("evidence", provider);
        login(LoginUser.operator("OPS", "Ops", List.of(), List.of()));
        var c = policy.draft(true);
        assertThat(c.actorNo()).isEqualTo("OPS");
        assertThat(c.platformOperator()).isTrue();
        assertThat(c.capabilities()).isEqualTo(new PricingCapabilities(0, 0, false, false, false));
        assertThat(c.expiresAt()).isEqualTo(Instant.EPOCH);
        assertThat(c.verifiedTaxProfiles()).isEmpty();
        verifyNoInteractions(provider, stores);
    }

    @Test
    void realmsCannotCrossConfigurationBoundariesAndConsumersHaveNoEntry() {
        login(LoginUser.consumer("USR", "C"));
        assertThatThrownBy(() -> policy.draft(true)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policy.draft(false)).isInstanceOf(BizException.class);
        login(LoginUser.merchant("ACT", "B"));
        assertThatThrownBy(() -> policy.draft(true)).isInstanceOf(BizException.class);
        login(LoginUser.operator("OPS", "Ops", List.of(), List.of()));
        assertThatThrownBy(() -> policy.draft(false)).isInstanceOf(BizException.class);
    }

    @Test
    void merchantUsesCurrentAuthorizedStoreAndRechecksDirectoryOwnership() {
        merchant();
        var c = policy.draft(false);
        assertThat(c.entityNo()).isEqualTo("ENTITY");
        assertThat(c.storeNo()).isEqualTo("STORE");
        assertThat(c.actorNo()).isEqualTo("ACT");
        when(stores.cards(List.of("STORE"))).thenReturn(Map.of("STORE", card("OTHER")));
        assertThatThrownBy(() -> policy.draft(false)).isInstanceOf(BizException.class);
        when(stores.cards(List.of("STORE"))).thenReturn(Map.of());
        assertThatThrownBy(() -> policy.draft(false)).isInstanceOf(BizException.class);
        BizContext.set(new BizContext("ENTITY", Set.of(), Set.of(), Set.of("ANOTHER"), "STORE", false));
        assertThatThrownBy(() -> policy.draft(false)).isInstanceOf(BizException.class);
    }

    @Test
    void missingOrAmbiguousProvidersCannotPublishMerchantConfiguration() {
        merchant();
        assertBlocked(() -> policy.publication(false, "PC1"));
        beans.registerSingleton("one", mock(PricingConfigEvidencePort.class));
        beans.registerSingleton("two", mock(PricingConfigEvidencePort.class));
        assertBlocked(() -> policy.publication(false, "PC1"));
    }

    @Test
    void globalTemplateDefinitionNeedsNoFinancialOwnerAndGrantsNoCapabilities() {
        login(LoginUser.operator("OPS", "Ops", List.of(), List.of()));
        var owner = mock(PricingConfigEvidencePort.class);
        beans.registerSingleton("unavailable", owner);
        var context = policy.publication(true, "PT1");
        assertThat(context.platformOperator()).isTrue();
        assertThat(context.capabilities()).isEqualTo(new PricingCapabilities(0, 0, false, false, false));
        assertThat(context.capabilityRef()).startsWith("DEF").hasSize(32);
        assertThat(context.expiresAt()).isAfter(Instant.now()).isBeforeOrEqualTo(Instant.now().plusSeconds(300));
        verifyNoInteractions(owner);
    }

    @Test
    void evidenceMustMatchResourceAndStoreAndMustStillBeFresh() {
        merchant();
        var provider = mock(PricingConfigEvidencePort.class);
        beans.registerSingleton("evidence", provider);
        var scope = new Scope("MAIN", "ENTITY", "STORE", "PC1", false);
        when(provider.load(scope)).thenReturn(null);
        assertBlocked(() -> policy.publication(false, "PC1"));
        when(provider.load(scope)).thenReturn(evidence(scope, Instant.EPOCH));
        assertBlocked(() -> policy.publication(false, "PC1"));
        when(provider.load(scope)).thenReturn(evidence(scope, Instant.now().plusSeconds(3600)));
        assertBlocked(() -> policy.publication(false, "PC1"));
        var wrong = new Scope("MAIN", "ENTITY", "OTHER", "PC1", false);
        when(provider.load(scope)).thenReturn(evidence(wrong, Instant.now().plusSeconds(60)));
        assertBlocked(() -> policy.publication(false, "PC1"));
        var expected = evidence(scope, Instant.now().plusSeconds(60));
        when(provider.load(scope)).thenReturn(expected);
        var context = policy.publication(false, "PC1");
        assertThat(context.capabilityRef()).isEqualTo("VERIFIED_REF");
        assertThat(context.verifiedTaxProfiles()).containsExactly("APPROVED_PROFILE");
        assertThat(context.capabilities()).isEqualTo(expected.capabilities());
    }

    @Test
    void emptySourcesAndProviderFailureNeverBecomeDraftFallbackForPublication() {
        merchant();
        var provider = mock(PricingConfigEvidencePort.class);
        beans.registerSingleton("evidence", provider);
        var scope = new Scope("MAIN", "ENTITY", "STORE", "PC1", false);
        when(provider.load(scope)).thenReturn(new Evidence(scope, new PricingCapabilities(1, 1, false, false, false),
                "REF", Instant.now().plusSeconds(60), Set.of(), List.of()));
        assertBlocked(() -> policy.publication(false, "PC1"));
        when(provider.load(scope)).thenThrow(new IllegalStateException("owner unavailable"));
        assertThatThrownBy(() -> policy.publication(false, "PC1"))
                .isInstanceOf(IllegalStateException.class);
    }

    private void merchant() {
        login(LoginUser.merchant("ACT", "B"));
        BizContext.set(new BizContext("ENTITY", Set.of(), Set.of(), Set.of("STORE"), "STORE", true));
        when(stores.cards(List.of("STORE"))).thenReturn(Map.of("STORE", card("ENTITY")));
    }

    private static StoreDirectoryPort.StoreCard card(String entity) {
        return new StoreDirectoryPort.StoreCard("STORE", "Store", entity, "", "ACTIVE", true,
                "", "", null, null, 0, 0);
    }

    private static Evidence evidence(Scope scope, Instant expiry) {
        return new Evidence(scope, new PricingCapabilities(1, 1, false, false, false), "VERIFIED_REF", expiry,
                Set.of("APPROVED_PROFILE"), List.of(new PricingSourceRef("OWNER", "CAPABILITY", "V1", 1, null, null)));
    }

    private static void assertBlocked(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
    }

    static void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }
}
