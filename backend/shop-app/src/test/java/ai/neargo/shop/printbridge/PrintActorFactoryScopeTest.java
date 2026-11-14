package ai.neargo.shop.printbridge;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.print.api.PrintFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PrintActorFactoryScopeTest {
    private final MchStoreMapper stores = mock(MchStoreMapper.class);
    private final PrintActorFactory factory = new PrintActorFactory(stores);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void login(DataScopeSpec scope) {
        var user = new LoginUser(ai.neargo.shop.auth.Realm.OPERATOR, ai.neargo.auth.store.SubjectKind.OPS,
                "OP", "operator", List.of(), List.of(), "MAIN", scope);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }

    @Test void requestCannotExpandTenantOrMerchantAuthorization() {
        login(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E1")));
        assertThatThrownBy(() -> factory.operator(null, "OTHER", "E1", "S1"))
                .isInstanceOf(PrintFailure.class);
        assertThatThrownBy(() -> factory.operator(null, "MAIN", "E2", "S1"))
                .isInstanceOf(PrintFailure.class);
        verifyNoInteractions(stores);
    }

    @Test void missingScopeAndUnsupportedDimensionsFailClosedBeforeReading() {
        login(null);
        assertThatThrownBy(() -> factory.operator(null, "MAIN", "E1", "S1"))
                .isInstanceOf(PrintFailure.class);
        login(DataScopeSpec.of(ScopeDim.COMMUNITY, Set.of("C1")));
        assertThatThrownBy(() -> factory.operator(null, "MAIN", "E1", "S1"))
                .isInstanceOf(PrintFailure.class);
        verifyNoInteractions(stores);
    }

    @Test void filteredOutStoreIsDeniedEvenForAnAllowedMerchant() {
        login(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E1")));
        assertThatThrownBy(() -> factory.operator(null, "MAIN", "E1", "S1"))
                .isInstanceOf(PrintFailure.class);
        verify(stores).selectOne(any());
    }

    @Test void actorUsesOnlyVerifiedStoreOwnership() {
        login(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E1")));
        var store = new MchStore();
        store.setTenantNo("MAIN"); store.setEntityNo("E1"); store.setStoreNo("S1");
        when(stores.selectOne(any())).thenReturn(store);
        var actor = factory.operator("KEY", "MAIN", "E1", "S1");
        assertThat(actor.entityNos()).containsExactly("E1");
        assertThat(actor.storeNos()).containsExactly("S1");
        assertThat(actor.idempotencyKey()).isEqualTo("KEY");
        store.setEntityNo("E2");
        assertThatThrownBy(() -> factory.operator(null, "MAIN", "E1", "S1"))
                .isInstanceOf(PrintFailure.class);
    }
}
