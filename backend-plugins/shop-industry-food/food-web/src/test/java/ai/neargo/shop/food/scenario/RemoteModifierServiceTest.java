package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.foodclient.RemoteModifierService;
import ai.neargo.shop.food.spi.ModifierInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteModifierServiceTest {
    @Test void typedCommandsPreserveTrustedScopeAndCasWithoutRetry() {
        var api = mock(ModifierInternalApi.class);
        var service = new RemoteModifierService(api);
        var req = new CreateModifierReq("珍珠", "ADDON", "SKU", List.of());
        when(api.createModifier(any())).thenReturn(new CreateModifierResultVO("MOD", 0, "DRAFT"));
        assertThat(service.createModifier("E", "S", "actor", req, "key").configNo()).isEqualTo("MOD");
        verify(api).createModifier(new ModifierInternalApi.CreateCommand("E", "S", "actor", "key", req));
        var update = new UpdateModifierReq("珍珠", "ADDON", null, List.of(), 0L);
        when(api.updateModifier(any())).thenReturn(new ModifierMutationVO("MOD", 1));
        assertThat(service.updateModifier("E", "S", "MOD", "actor", update, "next").version()).isEqualTo(1);
        verify(api).updateModifier(new ModifierInternalApi.UpdateCommand("E", "S", "MOD", "actor", "next", update));
        verifyNoMoreInteractions(api);
    }

    @Test void malformedMutationAndPageResponsesNeverBecomeSuccess() {
        var api = mock(ModifierInternalApi.class);
        var service = new RemoteModifierService(api);
        var req = new UpdateModifierReq("珍珠", "ADDON", null, List.of(), 0L);
        when(api.updateModifier(any())).thenReturn(new ModifierMutationVO("OTHER", 1));
        assertThatThrownBy(() -> service.updateModifier("E", "S", "MOD", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        when(api.updateModifier(any())).thenReturn(new ModifierMutationVO("MOD", 2));
        assertThatThrownBy(() -> service.updateModifier("E", "S", "MOD", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        var view = new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                new ModifierConfigVO.Data("珍珠", null, null, List.of()));
        when(api.listModifiers(any())).thenReturn(PageData.of(List.of(view), 1, 1, 20));
        assertThat(service.listModifiers("E", "S", null).records()).containsExactly(view);
        when(api.listModifiers(any())).thenReturn(PageData.of(List.of(view, view), 2, 1, 20));
        assertThatThrownBy(() -> service.listModifiers("E", "S", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void businessAndTimeoutErrorsNeverRetry() {
        for (int status : new int[]{400,403,404,409,503,0}) {
            var api = mock(ModifierInternalApi.class);
            when(api.listModifiers(any())).thenThrow(new ServiceCallException(
                    status == 0 ? CallOutcome.TIMEOUT : CallOutcome.REMOTE_ERROR, ServiceName.FOOD, status, "safe"));
            var expected = status >= 400 && status < 500 ? BizException.class : ServiceCallException.class;
            assertThatThrownBy(() -> new RemoteModifierService(api).listModifiers("E", "S", null))
                    .isInstanceOf(expected);
            verify(api).listModifiers(new ModifierInternalApi.Lookup("E", "S", null));
            verifyNoMoreInteractions(api);
        }
    }
}
