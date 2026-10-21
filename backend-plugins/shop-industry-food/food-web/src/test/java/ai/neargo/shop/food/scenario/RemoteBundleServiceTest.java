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
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.foodclient.RemoteBundleService;
import ai.neargo.shop.food.spi.BundleInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RemoteBundleServiceTest {
    @Test void numericRevisionTokensCannotBeRoundedOrDefaultedInWireReceipts() {
        var json = new ObjectMapper();
        for (String bad : List.of("0.5", "\"0\"", "null", "-1")) {
            assertThatThrownBy(() -> json.readValue("{\"configNo\":\"MEAL\",\"version\":" + bad
                    + ",\"status\":\"DRAFT\"}", CreateBundleResultVO.class)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> json.readValue("{\"configNo\":\"MEAL\",\"version\":" + bad + "}",
                    BundleMutationVO.class)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> json.readValue("{\"configNo\":\"MEAL\",\"version\":0,\"status\":\"DRAFT\","
                    + "\"data\":{\"configVersion\":" + bad
                    + ",\"groups\":[],\"choices\":[]}}", BundleConfigVO.class)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> json.readValue("{\"configNo\":\"MEAL\",\"version\":" + bad
                    + ",\"status\":\"DRAFT\",\"data\":{\"configVersion\":0,\"groups\":[],\"choices\":[]}}",
                    BundleConfigVO.class)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> json.readValue("{\"configNo\":\"MEAL\",\"status\":\"DRAFT\"}",
                CreateBundleResultVO.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"configNo\":\"MEAL\"}", BundleMutationVO.class))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void typedCommandsPreserveTrustedScopeAndCasWithoutRetry() {
        var api = mock(BundleInternalApi.class);
        var service = new RemoteBundleService(api);
        var req = new CreateBundleReq("MEAL");
        when(api.createBundle(any())).thenReturn(new CreateBundleResultVO("MEAL", 0, "DRAFT"));
        assertThat(service.createBundle("E", "S", "actor", req, "key").configNo()).isEqualTo("MEAL");
        verify(api).createBundle(new BundleInternalApi.CreateCommand("E", "S", "actor", "key", req));
        var update = new ReplaceBundleDefinitionReq(0L, List.of(), List.of());
        when(api.replaceDefinition(any())).thenReturn(new BundleMutationVO("MEAL", 1));
        assertThat(service.replaceDefinition("E", "S", "MEAL", "actor", update, "next").version()).isEqualTo(1);
        verify(api).replaceDefinition(new BundleInternalApi.UpdateCommand("E", "S", "MEAL", "actor", "next", update));
        when(api.getBundle(any())).thenReturn(new GetBundleVO(new BundleConfigVO("MEAL",0,"DRAFT",
                new BundleConfigVO.Data(0,null,List.of(),List.of()))));
        assertThat(service.getBundle("E","S","MEAL").config().configNo()).isEqualTo("MEAL");
        verify(api).getBundle(new BundleInternalApi.GetQuery("E","S","MEAL"));
        verifyNoMoreInteractions(api);
    }

    @Test void malformedMutationAndPageResponsesNeverBecomeSuccess() {
        var api = mock(BundleInternalApi.class);
        var service = new RemoteBundleService(api);
        var req = new ReplaceBundleDefinitionReq(0L, List.of(), List.of());
        when(api.replaceDefinition(any())).thenReturn(new BundleMutationVO("OTHER", 1));
        assertThatThrownBy(() -> service.replaceDefinition("E", "S", "MEAL", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        when(api.replaceDefinition(any())).thenReturn(new BundleMutationVO("MEAL", 2));
        assertThatThrownBy(() -> service.replaceDefinition("E", "S", "MEAL", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        var view = new BundleConfigVO("MEAL", 0, "DRAFT",
                new BundleConfigVO.Data(0, null, List.of(), List.of()));
        when(api.listBundles(any())).thenReturn(PageData.of(List.of(view), 1, 1, 20));
        assertThat(service.listBundles("E", "S", null).records()).containsExactly(view);
        when(api.listBundles(any())).thenReturn(PageData.of(List.of(view, view), 2, 1, 20));
        assertThatThrownBy(() -> service.listBundles("E", "S", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void businessAndTimeoutErrorsNeverRetry() {
        for (int status : new int[]{400,403,404,409,503,0}) {
            var api = mock(BundleInternalApi.class);
            when(api.listBundles(any())).thenThrow(new ServiceCallException(
                    status == 0 ? CallOutcome.TIMEOUT : CallOutcome.REMOTE_ERROR, ServiceName.FOOD, status, "safe"));
            var expected = status >= 400 && status < 500 ? BizException.class : ServiceCallException.class;
            assertThatThrownBy(() -> new RemoteBundleService(api).listBundles("E", "S", null))
                    .isInstanceOf(expected);
            verify(api).listBundles(new BundleInternalApi.Lookup("E", "S", null));
            verifyNoMoreInteractions(api);
        }
    }
}
