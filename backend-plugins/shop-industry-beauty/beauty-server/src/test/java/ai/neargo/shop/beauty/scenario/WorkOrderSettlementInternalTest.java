package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import java.time.Instant;
import java.util.List;
import ai.neargo.shop.beauty.server.api.InternalWorkOrderSettlementController;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.beauty.spi.BeautySettlementInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class WorkOrderSettlementInternalTest {
    <T> InternalCall<T> call(String realm, String action, String target, T request) {
        return new InternalCall<>(new InternalRequestBinding(1, BeautySettlementInternalApi.ENDPOINT, action,
                new DelegatedIdentity("MAIN", realm, realm.equals("MERCHANT") ? "MCH" : "USR", "A"),
                realm.equals("MERCHANT") ? "E" : null, realm.equals("MERCHANT") ? "S" : null, target), request);
    }
    @Test void tokenAndBoundHumanRoutePrecedePayloadAndOnlyCurrentScopeReachesBillOwner() {
        var service = mock(WorkOrderSettlementService.class);
        try (var f = Validation.buildDefaultValidatorFactory()) {
            var controller = new InternalWorkOrderSettlementController(service, f.getValidator(), "wire");
            var bad = call("MERCHANT", BeautySettlementInternalApi.READ, "WO", new BeautySettlementInternalApi.ReadReq("bad no"));
            assertThat(controller.read(bad, "wrong").getStatusCode().value()).isEqualTo(401);
            assertThat(controller.read(bad, "wire").getStatusCode().value()).isEqualTo(400);
            assertThat(controller.read(call("CONSUMER", BeautySettlementInternalApi.READ, "WO",
                    new BeautySettlementInternalApi.ReadReq("WO")), "wire").getStatusCode().value()).isEqualTo(400);
            assertThat(controller.read(call("MERCHANT", BeautySettlementInternalApi.READ, "FOREIGN",
                    new BeautySettlementInternalApi.ReadReq("WO")), "wire").getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(service);
            var state = new WorkOrderSettlementVO(new WorkOrderBillVO("WO", 1, "local", Instant.now(),
                    List.of(), List.of(), false, List.of("NO_SOURCE")), "a".repeat(64), List.of(),
                    false, false, List.of(), List.of("SOURCE_INCOMPLETE"));
            when(service.get("E", "S", "A", "WO")).thenReturn(state);
            var good = call("MERCHANT", BeautySettlementInternalApi.READ, "WO", new BeautySettlementInternalApi.ReadReq("WO"));
            assertThat(controller.read(good, "wire").getStatusCode().value()).isEqualTo(200);
            verify(service).get("E", "S", "A", "WO");
            var request = new PreviewWorkOrderSettlementReq("a".repeat(64), "CNY", 0L, 10L, "CASH");
            when(service.preview("E", "S", "A", "WO", request)).thenReturn(new WorkOrderSettlementPreviewVO(
                    state, "CNY", 0, 10, "CASH", false, List.of("CLOSED")));
            assertThat(controller.preview(call("MERCHANT", BeautySettlementInternalApi.PREVIEW, "WO",
                    new BeautySettlementInternalApi.PreviewReq("WO", request)), "wire").getStatusCode().value()).isEqualTo(200);
            verify(service).preview("E", "S", "A", "WO", request);
        }
    }
}
