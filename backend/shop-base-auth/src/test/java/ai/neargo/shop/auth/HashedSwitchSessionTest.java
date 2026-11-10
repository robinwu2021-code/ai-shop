package ai.neargo.shop.auth;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HashedSwitchSessionTest {
    @Test void hashLookupAndRevocationAreBoundToExactSourceAndPrincipal() {
        var store=new MemoryTokenStore(Duration.ofMinutes(5));
        var user=LoginUser.merchantByUser("U", "owner");
        String source=store.issue(TokenStore.SessionData.of(user));
        String sibling=store.issue(TokenStore.SessionData.of(user));
        String hash=TokenHash.of(source);
        assertTrue(store.getByHash(hash,"other").isEmpty());
        store.revokeByHash(hash,"other");
        assertTrue(store.get(source).isPresent());
        assertTrue(store.getByHash(hash,"U").isPresent());
        store.revokeByHash(hash,"U");
        assertTrue(store.get(source).isEmpty());
        assertTrue(store.get(sibling).isPresent());
    }

    @Test void routingFacadeNeverGuessesThePoolFromAHash() {
        var store=new MemoryTokenStore(Duration.ofMinutes(5));
        var routing=new RealmRoutingTokenStore(Map.of(Realm.CONSUMER,store,Realm.MERCHANT,store,Realm.OPERATOR,store));
        assertThrows(UnsupportedOperationException.class,()->routing.getByHash("hash","U"));
        assertThrows(UnsupportedOperationException.class,()->routing.revokeByHash("hash","U"));
    }
}
