package ai.neargo.commerce.api.offline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Terms are frozen into hashed payment snapshots written by one instance and re-read by another.
 * Their map order must not depend on the JVM (Map.copyOf iteration order is salted per JVM).
 */
class OfflineCollectionTermsTest {
    static OfflineCollectionTerms terms(Map<String, String> instructions) {
        return new OfflineCollectionTerms(
                "profile", 1, "platform", "TWD", "account-key", "w1", 86400000, instructions);
    }

    @Test
    void instructionOrderIsSortedWhateverTheInsertionOrder() {
        var reversed = new LinkedHashMap<String, String>();
        for (String k : List.of("zeta", "kappa", "bankCode", "alpha", "accountName", "mu"))
            reversed.put(k, k.toUpperCase());
        var sorted = new ArrayList<>(reversed.keySet());
        Collections.sort(sorted);
        assertEquals(sorted, List.copyOf(terms(reversed).instructions().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> terms(reversed).instructions().put("x", "y"));
    }

    @Test
    void voidDispositionsAreSortedToo() {
        var m = new LinkedHashMap<String, String>();
        m.put("U9", "returned");
        m.put("U1", "returned");
        var c = new OfflinePurchaseFacade.VoidCommand(null, "a", "r", "why", "e", m);
        assertEquals(List.of("U1", "U9"), List.copyOf(c.unmatchedDispositions().keySet()));
    }
}
