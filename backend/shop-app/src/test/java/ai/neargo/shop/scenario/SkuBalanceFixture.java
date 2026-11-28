package ai.neargo.shop.scenario;

import org.springframework.jdbc.core.JdbcTemplate;

/** Creates the shared inventory balance for fixtures that do not use store-level stock. */
final class SkuBalanceFixture {
    private SkuBalanceFixture() {}

    static void seed(JdbcTemplate jdbc, String sku, int stock, int locked) {
        int count = jdbc.update("""
                INSERT INTO prd_store_sku(tenant_no,store_no,entity_no,sku_no,market,sellable_qty,locked_qty)
                SELECT tenant_no,'',entity_no,sku_no,market,?,? FROM prd_sku
                WHERE sku_no=? AND deleted=0 ORDER BY id LIMIT 1
                """, stock, locked, sku);
        if (count != 1) throw new IllegalStateException("Missing SKU for fixture balance: " + sku);
    }
}
