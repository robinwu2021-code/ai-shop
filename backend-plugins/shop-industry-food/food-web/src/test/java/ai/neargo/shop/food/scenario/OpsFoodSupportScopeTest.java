package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.api.ops.OpsFoodSupportController;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.service.FoodSupportService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

class OpsFoodSupportScopeTest {
    private final FoodSupportService service = mock(FoodSupportService.class);
    private final StoreDirectoryPort stores = mock(StoreDirectoryPort.class);
    private final OpsFoodSupportController controller =
            new OpsFoodSupportController(service, stores);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(DataScopeSpec scope) {
        var user =
                new LoginUser(
                        Realm.OPERATOR,
                        SubjectKind.OPS,
                        "OP",
                        "support",
                        List.of(),
                        List.of(),
                        "MAIN",
                        scope);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private StoreDirectoryPort.StoreCard store(String entity) {
        return new StoreDirectoryPort.StoreCard(
                "S", "store", entity, null, "ACTIVE", true, null, null, null, null, 0, 0);
    }

    @Test
    void merchantBoundOperatorCannotProbeAnotherMerchant() {
        login(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("M1")));
        assertThatThrownBy(() -> controller.cases("M2", "S", "OPEN"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(service, stores);
    }

    @Test
    void guessedStoreCannotBorrowAuthorizedMerchantScope() {
        login(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("M1")));
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", store("M2")));
        assertThatThrownBy(() -> controller.readiness("M1", "S")).isInstanceOf(BizException.class);
        verifyNoInteractions(service);
    }

    @Test
    void missingUnsupportedAndConsumerScopesFailClosed() {
        login(null);
        assertThatThrownBy(() -> controller.cases("M1", "S", null))
                .isInstanceOf(BizException.class);
        login(DataScopeSpec.of(ScopeDim.COMMUNITY, Set.of("COMMUNITY")));
        assertThatThrownBy(() -> controller.cases("M1", "S", null))
                .isInstanceOf(BizException.class);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                LoginUser.consumer("U", "buyer"), null, List.of()));
        assertThatThrownBy(() -> controller.cases("M1", "S", null))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(service, stores);
    }

    @Test
    void originalBatchUnknownStateRemainsReadOnlyAndUsesAuthorizedOwner() {
        login(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("M1")));
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", store("M1")));
        Source source = new Source("BATCH", "B1");
        Evidence evidence = new Evidence(source, "UNKNOWN", 9, "hash", false, "ACTOR");
        when(service.evidence("M1", "S", source)).thenReturn(evidence);
        assertThat(controller.evidence("M1", "S", "BATCH", "B1")).isEqualTo(evidence);
        verify(service).evidence("M1", "S", source);
        verify(service, never()).readiness();
    }
}
