package ai.neargo.shop.print;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;

import org.junit.jupiter.api.Test;

class PrintStatePolicyTest {
    final PrintStatePolicy policy = new PrintStatePolicy();

    PrintTransition observe(
            PrintJobStatus before,
            ObservedPrintStatus status,
            PrintEvidenceLevel evidence,
            boolean terminated,
            boolean partial) {
        return policy.decide(
                new PrintStateSnapshot(before, false, false, 0, 3),
                new PrintObservation(status, evidence, terminated, partial));
    }

    @Test
    void queueAcceptanceNeverProvesCompletion() {
        var r =
                observe(
                        PrintJobStatus.DISPATCHING,
                        ObservedPrintStatus.ACCEPTED,
                        PrintEvidenceLevel.NONE,
                        false,
                        false);
        assertEquals(PrintJobStatus.ACCEPTED, r.status());
        assertFalse(r.releaseGate());
        assertFalse(r.safeToRetry());
    }

    @Test
    void uncertainOrPartialOutputNeverAutomaticallyRetries() {
        for (var status : ObservedPrintStatus.values()) {
            var r = observe(PrintJobStatus.PRINTING, status, PrintEvidenceLevel.NONE, false, true);
            assertEquals(PrintJobStatus.UNKNOWN, r.status());
            assertFalse(r.releaseGate());
            assertFalse(r.safeToRetry());
        }
    }

    @Test
    void onlyProvenNotAcceptedMayRetry() {
        assertFalse(
                observe(
                                PrintJobStatus.DISPATCHING,
                                ObservedPrintStatus.NOT_ACCEPTED,
                                PrintEvidenceLevel.NONE,
                                false,
                                false)
                        .safeToRetry());
        var r =
                observe(
                        PrintJobStatus.DISPATCHING,
                        ObservedPrintStatus.NOT_ACCEPTED,
                        PrintEvidenceLevel.NONE,
                        true,
                        false);
        assertEquals(PrintJobStatus.RETRY_WAIT, r.status());
        assertTrue(r.safeToRetry());
        assertTrue(r.releaseGate());
    }

    @Test
    void insufficientCompletionEvidenceKeepsGate() {
        assertEquals(
                PrintJobStatus.UNKNOWN,
                observe(
                                PrintJobStatus.ACCEPTED,
                                ObservedPrintStatus.CONFIRMED,
                                PrintEvidenceLevel.NONE,
                                true,
                                false)
                        .status());
        for (var e :
                new PrintEvidenceLevel[] {
                    PrintEvidenceLevel.DEVICE_CONFIRMED,
                    PrintEvidenceLevel.PROVIDER_CONFIRMED,
                    PrintEvidenceLevel.MANUAL_CONFIRMED
                })
            assertEquals(
                    PrintJobStatus.COMPLETED,
                    observe(PrintJobStatus.ACCEPTED, ObservedPrintStatus.CONFIRMED, e, true, false)
                            .status());
    }

    @Test
    void terminalStatesCannotBeReopenedByLateReceipts() {
        for (var before :
                new PrintJobStatus[] {
                    PrintJobStatus.COMPLETED,
                    PrintJobStatus.FAILED,
                    PrintJobStatus.CANCELLED,
                    PrintJobStatus.STALE
                })
            for (var status : ObservedPrintStatus.values()) {
                var r = observe(before, status, PrintEvidenceLevel.PROVIDER_CONFIRMED, true, false);
                assertEquals(before, r.status());
                assertFalse(r.releaseGate());
            }
    }

    @Test
    void retryBudgetIsFinite() {
        var r =
                policy.decide(
                        new PrintStateSnapshot(PrintJobStatus.DISPATCHING, false, false, 3, 3),
                        new PrintObservation(
                                ObservedPrintStatus.NOT_ACCEPTED,
                                PrintEvidenceLevel.NONE,
                                true,
                                false));
        assertEquals(PrintJobStatus.FAILED, r.status());
        assertFalse(r.safeToRetry());
    }
}
