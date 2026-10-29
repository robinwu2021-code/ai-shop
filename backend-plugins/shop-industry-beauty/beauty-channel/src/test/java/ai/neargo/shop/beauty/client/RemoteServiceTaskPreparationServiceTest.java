package ai.neargo.shop.beauty.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.beauty.spi.BeautyTaskPreparationInternalApi;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;

import org.junit.jupiter.api.Test;

/**
 * The new public preparation facade actually consumes its single finite standalone private route.
 */
class RemoteServiceTaskPreparationServiceTest {
    private final InternalBindings bindings = new InternalBindings("secret");
    private final BeautyTaskPreparationInternalApi api =
            mock(BeautyTaskPreparationInternalApi.class);
    private final DelegatedIdentity actor =
            new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor");

    @Test
    void realClientBindsTrustedNamespaceAndChecksTheReturnedTask() {
        when(api.prepareTask(any()))
                .thenAnswer(
                        invocation -> {
                            InternalCall<BeautyTaskPreparationInternalApi.PrepareTaskCall> call =
                                    invocation.getArgument(0);
                            assertEquals("BEAUTY_TASK_PREPARATION", call.binding().endpoint());
                            assertEquals("PREPARE_TASK", call.binding().action());
                            assertEquals(actor, call.binding().identity());
                            assertEquals("key", call.request().requestKey());
                            assertEquals("E", call.request().entityNo());
                            return bindings.reply(call, view("task"));
                        });
        var result =
                DelegatedIdentityContext.call(
                        actor,
                        () ->
                                client().prepareTask(
                                                "E",
                                                "S",
                                                "actor",
                                                "work",
                                                "task",
                                                new PrepareServiceTaskReq(0, 1, 1),
                                                "key"));
        assertEquals("submission", result.operationNo());
        assertFalse(result.canStart());
    }

    @Test
    void changedTaskResponseCannotBeAcceptedEvenWithAnEchoedCorrectDigest() {
        when(api.prepareTask(any()))
                .thenAnswer(
                        invocation -> {
                            InternalCall<BeautyTaskPreparationInternalApi.PrepareTaskCall> call =
                                    invocation.getArgument(0);
                            return bindings.reply(call, view("foreign"));
                        });
        assertThrows(
                BizException.class,
                () ->
                        DelegatedIdentityContext.call(
                                actor,
                                () ->
                                        client().prepareTask(
                                                        "E",
                                                        "S",
                                                        "actor",
                                                        "work",
                                                        "task",
                                                        new PrepareServiceTaskReq(0, 1, 1),
                                                        "key")));
    }

    private RemoteServiceTaskPreparationService client() {
        return new RemoteServiceTaskPreparationService(api, bindings);
    }

    private ServiceTaskPreparationVO view(String task) {
        return new ServiceTaskPreparationVO(
                "submission",
                "work",
                task,
                "execution",
                "STOCK_RETENTION_PENDING",
                "PREPARED",
                "UNKNOWN",
                "AUTHENTICATED_ORIGINAL_RECOVERY",
                "AUTHENTICATED_ORIGINAL_RECOVERY",
                null);
    }
}
