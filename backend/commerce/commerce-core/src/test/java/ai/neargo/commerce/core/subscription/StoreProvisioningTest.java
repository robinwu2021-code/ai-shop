package ai.neargo.commerce.core.subscription;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;

import org.junit.jupiter.api.Test;

class StoreProvisioningTest {
    @Test
    void initializationRejectsSystemAndCrossTenant() {
        var command = new InitializeStoreCommand(new StoreRef("a", "store"), "RETAIL", "op");
        for (var actor :
                java.util.List.of(
                        new ActorContext("a", SubjectKind.SYSTEM, "system", "system"),
                        new ActorContext("b", SubjectKind.ACCOUNT, "owner", "session"))) {
            assertThrows(
                    SecurityException.class,
                    () -> StoreProvisioningService.checkActor(actor, command));
        }
    }
}
