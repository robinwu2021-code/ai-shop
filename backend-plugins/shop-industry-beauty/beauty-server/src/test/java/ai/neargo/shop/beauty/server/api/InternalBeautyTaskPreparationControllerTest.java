package ai.neargo.shop.beauty.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.service.ServiceTaskPreparationService;
import ai.neargo.shop.beauty.spi.BeautyTaskPreparationInternalApi.PrepareTaskCall;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import org.junit.jupiter.api.Test;

/**
 * The actual standalone private route verifies token before target access and binds the complete
 * body namespace.
 */
class InternalBeautyTaskPreparationControllerTest {
    private final ServiceTaskPreparationService service = mock(ServiceTaskPreparationService.class);
    private final InternalBeautyTaskPreparationController controller =
            new InternalBeautyTaskPreparationController(service, "secret");

    @Test
    void tokenPrecedesMalformedBodyAndOwnerAccess() {
        assertEquals(401, controller.prepareTask(null, "wrong").getStatusCode().value());
        assertEquals(400, controller.prepareTask(null, "secret").getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void crossActorStoreAndTargetCannotReachPreparationService() {
        for (String[] body :
                new String[][] {
                    {"OTHER", "actor", "task"}, {"S", "other", "task"}, {"S", "actor", "foreign"}
                }) {
            var call =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    "BEAUTY_TASK_PREPARATION",
                                    "PREPARE_TASK",
                                    new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                                    "E",
                                    "S",
                                    "task"),
                            new PrepareTaskCall(
                                    "E",
                                    body[0],
                                    body[1],
                                    "work",
                                    body[2],
                                    new PrepareServiceTaskReq(0, 1, 1),
                                    "key"));
            assertEquals(400, controller.prepareTask(call, "secret").getStatusCode().value());
        }
        verifyNoInteractions(service);
    }
}
