package ai.neargo.shop.scenario;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real owning rows for synthetic merchant/store numbers used by full-context fixtures.
 *
 * <p>Order, points and coupon writes lock the owning entity (and store) through
 * {@code StoreOwnershipWritePort}; a bare number with no {@code mch_entity}/{@code mch_store} row is not an owner
 * and is rejected with CONFLICT. Inserts are idempotent so shared contexts can call this from every test.
 */
final class StoreOwnerFixture {
    private StoreOwnerFixture() {}

    static void entity(JdbcTemplate jdbc, String entityNo) {
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,tenant_no,created_at,updated_at)"
                        + " SELECT ?,?,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP"
                        + " WHERE NOT EXISTS(SELECT 1 FROM mch_entity WHERE entity_no=?)",
                entityNo, "Fixture owner " + entityNo, entityNo);
    }

    static void store(JdbcTemplate jdbc, String entityNo, String storeNo) {
        entity(jdbc, entityNo);
        jdbc.update("INSERT INTO mch_store(entity_no,store_no,name,status,tenant_no,created_at,updated_at)"
                        + " SELECT ?,?,?,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP"
                        + " WHERE NOT EXISTS(SELECT 1 FROM mch_store WHERE store_no=?)",
                entityNo, storeNo, "Fixture store " + storeNo, storeNo);
    }
}
