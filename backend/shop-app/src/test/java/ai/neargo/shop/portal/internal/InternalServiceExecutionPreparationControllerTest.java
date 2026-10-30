package ai.neargo.shop.portal.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

/** Actual controller entry points verify token before null target access or any owner call. */
class InternalServiceExecutionPreparationControllerTest {
    private static final String H = "a".repeat(64);
    private final ServiceExecutionPreparationPort owner =
            mock(ServiceExecutionPreparationPort.class);
    private final InternalServiceExecutionPreparationController controller =
            new InternalServiceExecutionPreparationController(owner, "secret");

    @Test
    void wrongTokenPrecedesNullBodyAndEveryOwnerRoute() {
        assertEquals(401, controller.referenceOriginal(null, "wrong").getStatusCode().value());
        assertEquals(401, controller.retainStock(null, "wrong").getStatusCode().value());
        assertEquals(401, controller.queryOriginalEvidence(null, "wrong").getStatusCode().value());
        assertEquals(401, controller.queryStockOriginal(null, "wrong").getStatusCode().value());
        verifyNoInteractions(owner);
    }

    @Test
    void authenticatedMalformedBodiesAreFiniteClientFailures() {
        assertEquals(400, controller.referenceOriginal(null, "secret").getStatusCode().value());
        assertEquals(400, controller.retainStock(null, "secret").getStatusCode().value());
        assertEquals(400, controller.queryOriginalEvidence(null, "secret").getStatusCode().value());
        verifyNoInteractions(owner);
    }

    @Test
    void humanEndpointActionScopeAndTargetAreRouteOwned() {
        var binding =
                new ExecutionBinding(
                        new ExecutionScope("MAIN", "E", "S"),
                        "BEAUTY",
                        "operation",
                        "order",
                        "sub",
                        "line",
                        1,
                        "work",
                        1,
                        "task",
                        0,
                        "binding",
                        1,
                        "execution",
                        1,
                        "submission",
                        "fence",
                        1,
                        1,
                        1,
                        H,
                        "definition",
                        H,
                        H,
                        H);
        for (String[] mismatch :
                new String[][] {
                    {"WRONG", "REFERENCE_ORIGINAL", "E", "execution"},
                    {"SERVICE_EXECUTION_PREPARATION", "RETAIN_STOCK", "E", "execution"},
                    {"SERVICE_EXECUTION_PREPARATION", "REFERENCE_ORIGINAL", "OTHER", "execution"},
                    {"SERVICE_EXECUTION_PREPARATION", "REFERENCE_ORIGINAL", "E", "foreign"}
                }) {
            var call =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    mismatch[0],
                                    mismatch[1],
                                    new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                                    mismatch[2],
                                    "S",
                                    mismatch[3]),
                            new ExecutionLookup(binding));
            assertEquals(400, controller.referenceOriginal(call, "secret").getStatusCode().value());
        }
        verifyNoInteractions(owner);
    }

    @Test
    void machineEvidenceObservesWithoutInstallingAnyEmployee() {
        var ref =
                new ExecutionReference(
                                "SERVICE_EXECUTION_REFERENCE_V1",
                                new ExecutionScope("MAIN", "E", "S"),
                                ExecutionReference.SourceKind.STORE_ORDER,
                                "MANUAL_EXECUTION_V1",
                                "execution",
                                "grant",
                                "unit",
                                "task",
                                "work",
                                1,
                                1,
                                H,
                                H,
                                H,
                                H,
                                H,
                                "fence",
                                1,
                                H,
                                "BTRC",
                                H,
                                "action",
                                H,
                                H,
                                null)
                        .signed();
        var call =
                new EvidenceCall(
                        new EvidenceBinding(
                                1,
                                "QUERY_ORIGINAL_EVIDENCE",
                                ref.scope(),
                                "execution",
                                ref.referenceHash()),
                        new ExecutionEvidenceLookup(ref));
        var before = SecurityContextHolder.getContext();
        when(owner.queryOriginalEvidence(call.request()))
                .thenAnswer(
                        invocation -> {
                            assertSame(before, SecurityContextHolder.getContext());
                            return new ExecutionOriginalEvidence(
                                            "SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1",
                                            ref,
                                            ExecutionOriginalEvidence.EvidenceOutcome.UNKNOWN,
                                            "UNKNOWN",
                                            null,
                                            Instant.EPOCH,
                                            null)
                                    .signed();
                        });
        assertEquals(200, controller.queryOriginalEvidence(call, "secret").getStatusCode().value());
        assertSame(before, SecurityContextHolder.getContext());
    }
}
