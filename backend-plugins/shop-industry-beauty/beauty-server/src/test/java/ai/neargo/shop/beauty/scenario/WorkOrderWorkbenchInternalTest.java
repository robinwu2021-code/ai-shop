package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.server.api.InternalWorkOrderWorkbenchController;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.beauty.spi.BeautyWorkOrderWorkbenchInternalApi;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import java.util.List;

class WorkOrderWorkbenchInternalTest {
    private InternalCall<WorkOrderQueryReq> call(int page, String realm) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        BeautyWorkOrderWorkbenchInternalApi.ENDPOINT,
                        BeautyWorkOrderWorkbenchInternalApi.ACTION,
                        new DelegatedIdentity(
                                "MAIN", realm, realm.equals("MERCHANT") ? "MCH" : "USR", "A"),
                        realm.equals("MERCHANT") ? "E" : null,
                        realm.equals("MERCHANT") ? "S" : null,
                        realm.equals("MERCHANT") ? "S" : null),
                new WorkOrderQueryReq(null, null, null, null, null, page, 20));
    }

    @Test
    void tokenAndRealmPrecedePayloadValidationAndInvalidPageNeverReadsOwner() {
        var service = mock(WorkOrderWorkbenchService.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var controller =
                    new InternalWorkOrderWorkbenchController(
                            service, factory.getValidator(), "wire");
            assertThat(controller.list(call(0, "MERCHANT"), "wrong").getStatusCode().value())
                    .isEqualTo(401);
            assertThat(controller.list(call(0, "MERCHANT"), "wire").getStatusCode().value())
                    .isEqualTo(400);
            assertThat(controller.list(call(1, "CONSUMER"), "wire").getStatusCode().value())
                    .isEqualTo(400);
            verifyNoInteractions(service);
            var q = call(1, "MERCHANT");
            when(service.list("E", "S", "A", q.request()))
                    .thenReturn(new PageData<>(List.of(), 0, 1, 20));
            assertThat(controller.list(q, "wire").getStatusCode().value()).isEqualTo(200);
            verify(service).list("E", "S", "A", q.request());
        }
    }
}
