package ai.neargo.shop.printbridge;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.print.api.PrintFailure;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class PrintActorFactoryTest {
    private static final class Facts implements MerchantScopeFactsPort {
        int reads;
        boolean owned;
        public boolean entityActive(String tenant, String entity) { return true; }
        public boolean storesOwned(String tenant, String entity, Set<String> stores) {
            reads++; return owned && stores.equals(Set.of("S1"));
        }
    }
    private final Facts merchants = new Facts();
    private final PrintActorFactory factory = new PrintActorFactory(merchants);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void operator(DataScopeSpec scope) {
        var user = LoginUser.operator("STAFF", "Operator", List.of(), List.of("ops:print:read"), scope);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    @Test void refusesForeignMerchantBeforeReadingOwnershipFacts() {
        operator(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E1")));
        assertThrows(PrintFailure.class, () -> factory.operator("K", "MAIN", "E2", "S2"));
        assertEquals(0, merchants.reads);
    }
    @Test void refusesForeignTenantBeforeReadingOwnershipFacts() {
        operator(DataScopeSpec.ALL);
        assertThrows(PrintFailure.class, () -> factory.operator("K", "OTHER", "E1", "S1"));
        assertEquals(0, merchants.reads);
    }
    @Test void actorCannotEnlargeAnAuthorizedMerchantToAnotherStore() {
        operator(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E1")));
        merchants.owned = true;
        var actor = factory.operator("K", "MAIN", "E1", "S1");
        assertEquals(Set.of("E1"), actor.entityNos());
        assertEquals(Set.of("S1"), actor.storeNos());
        assertThrows(PrintFailure.class, () -> factory.operator("K", "MAIN", "E1", "FOREIGN"));
    }
    @Test void pickupScopeDoesNotGrantMerchantWidePrintAccess() {
        operator(DataScopeSpec.of(ScopeDim.PICKUP, Set.of("P1")));
        assertThrows(PrintFailure.class, () -> factory.operator("K", "MAIN", "E1", "S1"));
        assertEquals(0, merchants.reads);
    }
}
