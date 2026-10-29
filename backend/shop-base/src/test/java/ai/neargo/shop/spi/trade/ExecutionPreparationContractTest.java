package ai.neargo.shop.spi.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** The finite bridge cannot grow machine acceptance or arbitrary payload selection unnoticed. */
class ExecutionPreparationContractTest {
    @Test
    void facadeExposesOnlyFourConsumedOperations() {
        assertEquals(
                Set.of(
                        "referenceOriginal",
                        "queryOriginalEvidence",
                        "retainStock",
                        "queryStockOriginal"),
                Arrays.stream(ServiceExecutionPreparationPort.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
    }

    @Test
    void machineBindingsCannotSelectHumanOrContinuationActions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionPreparationOriginalProtocol.EvidenceBinding(
                                1, "REFERENCE_ORIGINAL", null, "x", "x"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionPreparationOriginalProtocol.StockBinding(
                                1, "RETAIN_STOCK", null, "x", "x", "x", "x"));
    }

    @Test
    void everyEnvelopeHasConcretePayloadAndNoSavedHumanIdentity() {
        for (Class<?> type :
                new Class<?>[] {
                    ExecutionPreparationOriginalProtocol.EvidenceCall.class,
                    ExecutionPreparationOriginalProtocol.EvidenceReply.class,
                    ExecutionPreparationOriginalProtocol.StockCall.class,
                    ExecutionPreparationOriginalProtocol.StockReply.class
                }) {
            assertEquals(0, type.getTypeParameters().length);
            assertFalse(
                    Arrays.stream(type.getRecordComponents())
                            .anyMatch(
                                    component ->
                                            component.getType() == Object.class
                                                    || component.getName().contains("identity")));
        }
    }
}
