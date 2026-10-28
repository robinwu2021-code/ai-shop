package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts.Query;
import ai.neargo.shop.food.commerce.service.impl.FoodCatalogBindingServiceImpl;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.service.ConfigRevisionService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

class FoodCatalogBindingServiceTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final FoodCatalogBindingFixtures.MutableClock clock = new FoodCatalogBindingFixtures.MutableClock();
    final ConfigRevisionService menus = mock(ConfigRevisionService.class);
    final ChannelCatalogAuthorityService authorities = mock(ChannelCatalogAuthorityService.class);
    final FoodCatalogBindingServiceImpl service =
            new FoodCatalogBindingServiceImpl(f.json, menus, authorities, clock);
    final Query query = FoodCatalogBindingFixtures.QUERY;

    void valid() {
        when(menus.verifyMenuResource(query.menu())).thenReturn(f.menu());
        when(authorities.capture(query.connection())).thenReturn(f.authority());
    }

    @Test
    void capturesExactOriginalBindingAndPreservesEvidenceAndUnapprovedPolicyReference() {
        valid();
        var result = service.capture(query);
        assertThat(result.query()).isEqualTo(query);
        assertThat(result.binding().bindingNo()).isEqualTo("B");
        assertThat(result.context().pricePolicyRef()).isEqualTo("POLICY");
        assertThat(result.authority()).isEqualTo(f.authority());
        assertThat(result.menuProof().publication()).isEqualTo(f.menu().publication());
        assertThat(result.verifiedAt()).isEqualTo(FoodCatalogBindingFixtures.NOW);
        assertThat(result.validUntil()).isEqualTo(FoodCatalogBindingFixtures.NOW.plusSeconds(20));
        verify(menus, times(2)).verifyMenuResource(query.menu());
        verify(authorities).capture(query.connection());
    }

    @Test
    void originalMenuExpiryAlsoBoundsTheComposition() {
        valid();
        when(menus.verifyMenuResource(query.menu())).thenReturn(
                f.menu("Menu", f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"), true, 7));
        assertThat(service.capture(query).validUntil()).isEqualTo(FoodCatalogBindingFixtures.NOW.plusSeconds(7));
    }

    @Test
    void rejectsHistoricalResourceAndCrossScopeInputsBeforeOwnerReads() {
        var m = query.menu();
        var history = new MenuSourceContracts.Query(m.entityNo(), m.storeNo(), m.menuNo(), m.revisionNo(),
                m.kind(), m.resourceRef(), null, null, MenuSourceContracts.Mode.HISTORICAL);
        var offer = new MenuSourceContracts.Query("E", "S", "M", "MR", MenuSourceContracts.Kind.OFFER,
                "ITEM", "ITEM", null, MenuSourceContracts.Mode.CURRENT);
        var otherStore = new ChannelCatalogAuthorityContracts.Query("E", "OTHER", "CON", "CR");
        for (var input : List.of(new Query(history, query.connection(), "B"),
                new Query(offer, query.connection(), "B"), new Query(m, otherStore, "B"),
                new Query(m, query.connection(), " "))) {
            assertThatThrownBy(() -> service.capture(input)).isInstanceOfSatisfying(BizException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
        verifyNoInteractions(menus, authorities);
    }

    @Test
    void rejectsProviderAccountStoreCaseDifferencesAndInternalAccountFallback() {
        valid();
        for (var binding : List.of(f.binding("OTHER", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                f.binding("test", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                f.binding("TEST", "ACC", "REMOTE_STORE"),
                f.binding("TEST", "OTHER", "REMOTE_STORE"),
                f.binding("TEST", "REMOTE_ACCOUNT", "OTHER"))) {
            when(menus.verifyMenuResource(query.menu())).thenReturn(f.menu("Menu", binding, true, 25));
            conflict(() -> service.capture(query));
        }
    }

    @Test
    void neverTruncatesAnExternalAccountOrStoreToFitLegacyBindingFields() {
        valid();
        for (var authority : List.of(f.authority("FOOD", "INSTANT", "A".repeat(65), "REMOTE_STORE", 20),
                f.authority("FOOD", "INSTANT", "REMOTE_ACCOUNT", "S".repeat(65), 20))) {
            when(authorities.capture(query.connection())).thenReturn(authority);
            conflict(() -> service.capture(query));
        }
    }

    @Test
    void rejectsNonFoodAndNonInstantConnectionsDespiteValidOriginalGrants() {
        valid();
        for (var authority : List.of(f.authority("RETAIL", "INSTANT", "REMOTE_ACCOUNT", "REMOTE_STORE", 20),
                f.authority("FOOD", "PARCEL", "REMOTE_ACCOUNT", "REMOTE_STORE", 20))) {
            when(authorities.capture(query.connection())).thenReturn(authority);
            conflict(() -> service.capture(query));
        }
    }

    @Test
    void rejectsMissingChannelContextAndMissingRequestedBinding() {
        valid();
        conflict(() -> service.capture(new Query(query.menu(), query.connection(), "MISSING")));
        when(menus.verifyMenuResource(query.menu())).thenReturn(f.menu("Local", null, false, 25));
        conflict(() -> service.capture(query));
    }

    @Test
    void rejectsOriginalMenuChangesWhileReadingAuthority() {
        valid();
        when(menus.verifyMenuResource(query.menu())).thenReturn(f.menu(),
                f.menu("Changed", f.binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"), true, 25));
        conflict(() -> service.capture(query));
    }

    @Test
    void rechecksExpiryAfterTheLastOriginalRead() {
        valid();
        var proof = f.menu();
        when(menus.verifyMenuResource(query.menu())).thenAnswer(call -> proof).thenAnswer(call -> {
            clock.instant = FoodCatalogBindingFixtures.NOW.plusSeconds(21);
            return proof;
        });
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesOriginalReceiptsInsteadOfTrustingTypedObjects() {
        valid();
        var original = f.authority();
        when(authorities.capture(query.connection())).thenReturn(new ChannelCatalogAuthorityContracts.Facts(
                original.schemaVersion(), original.purpose(), original.query(), original.provider(),
                original.profile(), original.account(), original.connection(), original.grant(),
                original.identity(), original.verifiedAt(), original.validUntil(), "a".repeat(64)));
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
        when(authorities.capture(query.connection())).thenReturn(f.authority());
        var menu = f.menu();
        when(menus.verifyMenuResource(query.menu())).thenReturn(new MenuSourceContracts.Proof(
                menu.schemaVersion(), menu.query(), menu.publication(), menu.resource(),
                menu.verifiedAt(), menu.validUntil(), "a".repeat(64)));
        assertThatThrownBy(() -> service.capture(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preservesOriginalOwnerFailureWithoutFallbackAndRejectsOuterTransactions() {
        valid();
        var denied = BizException.of(ErrorCode.FORBIDDEN);
        when(authorities.capture(query.connection())).thenThrow(denied);
        assertThatThrownBy(() -> service.capture(query)).isSameAs(denied);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.capture(query))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OUTSIDE_TRANSACTION");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(menus).verifyMenuResource(query.menu());
    }

    private static void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
