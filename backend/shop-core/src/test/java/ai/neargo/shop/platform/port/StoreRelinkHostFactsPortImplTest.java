package ai.neargo.shop.platform.port;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exact host SQL evidence, independent of Pay/commerce and any production database. */
class StoreRelinkHostFactsPortImplTest {
    private JdbcTemplate jdbc;
    private StoreRelinkHostFactsPortImpl facts;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:relink_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ord_order(tenant_no VARCHAR(32), entity_no VARCHAR(64), store_no VARCHAR(64), order_no VARCHAR(64), status VARCHAR(24), deleted INT)");
        jdbc.execute("CREATE TABLE ord_sub_order(tenant_no VARCHAR(32), entity_no VARCHAR(64), store_no VARCHAR(64), order_no VARCHAR(64), sub_order_no VARCHAR(64), status VARCHAR(24), deleted INT)");
        jdbc.execute("CREATE TABLE ord_offline_record(tenant_no VARCHAR(32), entity_no VARCHAR(64), store_no VARCHAR(64), status VARCHAR(24), deleted INT)");
        jdbc.execute("CREATE TABLE ord_after_sale(tenant_no VARCHAR(32), entity_no VARCHAR(64), order_no VARCHAR(64), sub_order_no VARCHAR(64), status VARCHAR(24), deleted INT)");
        jdbc.execute("CREATE TABLE mch_entity_apply(tenant_no VARCHAR(32), entity_no VARCHAR(64), status VARCHAR(24), deleted INT)");
        for (String table : java.util.List.of("ord_checkout_operation", "ord_checkout_confirmation", "ord_ent_service_order", "ord_external_certificate_use", "pmt_apply", "ord_industry_order_source", "ord_channel_import", "ord_channel_admission_guard"))
            jdbc.execute("CREATE TABLE " + table + "(tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),deleted INT)");
        for (String table : java.util.List.of("pmt_coupon", "pmt_user_coupon", "pmt_coupon_issue", "mkt_coupon", "pmt_pay_coupon_projection", "pmt_migration_decision"))
            jdbc.execute("CREATE TABLE " + table + "(tenant_no VARCHAR(32),entity_no VARCHAR(64),deleted INT)");
        for (String table : java.util.List.of("ord_points_binding", "ord_points_source_fact"))
            jdbc.execute("CREATE TABLE " + table + "(store_no VARCHAR(64))");
        for (String table : java.util.List.of("prd_goods", "prd_store_stock", "prd_store_goods", "prd_store_price"))
            jdbc.execute("CREATE TABLE " + table + "(tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),deleted INT)");
        for (String table : java.util.List.of("prd_stock_replenishment", "prd_stock_replenishment_connection",
                "prd_stock_replenishment_command", "prd_stock_replenishment_movement", "prd_stock_replenishment_manual",
                "prd_stock_replenishment_return"))
            jdbc.execute("CREATE TABLE " + table + "(tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),deleted INT)");
        for (String table : java.util.List.of("prd_sku", "prd_goods_draft", "prd_stock_writer"))
            jdbc.execute("CREATE TABLE " + table + "(tenant_no VARCHAR(32),entity_no VARCHAR(64),deleted INT)");
        facts = new StoreRelinkHostFactsPortImpl(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings={"prd_stock_replenishment", "prd_stock_replenishment_connection", "prd_stock_replenishment_command",
            "prd_stock_replenishment_movement", "prd_stock_replenishment_manual", "prd_stock_replenishment_return"})
    void replenishmentHistoryBlocksRelinkEvenWithAnEmptyCatalog(String table) {
        jdbc.update("INSERT INTO " + table + " VALUES('OTHER','M','S',0)");
        assertFalse(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("INSERT INTO " + table + " VALUES('MAIN','OTHER','S',0)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO " + table + " VALUES('MAIN','M','SIBLING',0)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
    }

    @Test void replenishmentWriterFenceIsEntityHistory() {
        jdbc.update("INSERT INTO prd_stock_writer VALUES('MAIN','OTHER',0)");
        assertFalse(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("INSERT INTO prd_stock_writer VALUES('MAIN','M',0)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
    }

    @Test void emptyHostFactsDoNotInventFinancialEvidence() {
        var result = facts.inspect("MAIN", "M", "S");
        assertFalse(result.hasTrades());
        assertFalse(result.pendingApply());
    }

    @ParameterizedTest
    @ValueSource(strings={"ord_order", "ord_sub_order", "ord_offline_record"})
    void cancelledVoidedAndArchivedRowsStillCountAndTenantIsExplicit(String table) {
        // Table names are fixed test inputs, never API input.
        jdbc.update("INSERT INTO " + table + "(tenant_no,entity_no,store_no,status,deleted) VALUES('OTHER','M','S','VOID',1)");
        assertFalse(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("INSERT INTO " + table + "(tenant_no,entity_no,store_no,status,deleted) VALUES('MAIN','HISTORICAL_ENTITY','S','CANCELLED',1)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
    }

    @ParameterizedTest
    @ValueSource(strings={"ord_order", "ord_sub_order", "ord_offline_record"})
    void unattributableSourceEntityRowsCountButKnownSiblingAndOtherEntityDoNot(String table) {
        jdbc.update("INSERT INTO " + table + "(tenant_no,entity_no,store_no,status,deleted) VALUES('MAIN','M','SIBLING','DONE',0)");
        jdbc.update("INSERT INTO " + table + "(tenant_no,entity_no,store_no,status,deleted) VALUES('MAIN','OTHER',NULL,'DONE',0)");
        assertFalse(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("INSERT INTO " + table + "(tenant_no,entity_no,store_no,status,deleted) VALUES('MAIN','M',NULL,'DONE',1)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("UPDATE " + table + " SET store_no='' WHERE tenant_no='MAIN' AND entity_no='M'");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
    }

    @Test void afterSaleUsesSameTenantOrderAndSubOrderAndKeepsArchivedHistory() {
        jdbc.update("INSERT INTO ord_order VALUES('OTHER','M','S','O','DONE',0)");
        jdbc.update("INSERT INTO ord_sub_order VALUES('OTHER','M','S','O','SUB','DONE',0)");
        jdbc.update("INSERT INTO ord_after_sale VALUES('MAIN','OTHER','O','SUB','CLOSED',1)");
        assertFalse(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("INSERT INTO ord_sub_order VALUES('MAIN','OTHER','S','O','SUB','DONE',1)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("DELETE FROM ord_sub_order WHERE tenant_no='MAIN'");
        jdbc.update("INSERT INTO ord_order VALUES('MAIN','OTHER','S','O','CANCELLED',1)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
    }

    @Test void orphanAfterSaleOfSourceCountsButResolvedSiblingAndOtherEntityDoNot() {
        jdbc.update("INSERT INTO ord_order VALUES('MAIN','M','SIBLING','O','DONE',0)");
        jdbc.update("INSERT INTO ord_sub_order VALUES('MAIN','M','SIBLING','O','SUB','DONE',0)");
        jdbc.update("INSERT INTO ord_after_sale VALUES('MAIN','M','O','SUB','CLOSED',1)");
        jdbc.update("INSERT INTO ord_after_sale VALUES('MAIN','OTHER','MISSING','MISSING','CLOSED',1)");
        assertFalse(facts.inspect("MAIN", "M", "S").hasTrades());
        jdbc.update("INSERT INTO ord_after_sale VALUES('MAIN','M','MISSING','MISSING','CLOSED',1)");
        assertTrue(facts.inspect("MAIN", "M", "S").hasTrades());
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING", "REVIEWING"})
    void onlyLivePendingApplicationsOfSameTenantAndEntityBlock(String status) {
        jdbc.update("INSERT INTO mch_entity_apply VALUES('OTHER','M',?,0)", status);
        jdbc.update("INSERT INTO mch_entity_apply VALUES('MAIN','OTHER',?,0)", status);
        jdbc.update("INSERT INTO mch_entity_apply VALUES('MAIN','M',?,1)", status);
        jdbc.update("INSERT INTO mch_entity_apply VALUES('MAIN','M','APPROVED',0)");
        jdbc.update("INSERT INTO mch_entity_apply VALUES('MAIN','M','REJECTED',0)");
        assertFalse(facts.inspect("MAIN", "M", "S").pendingApply());
        jdbc.update("INSERT INTO mch_entity_apply VALUES('MAIN','M',?,0)", status);
        assertTrue(facts.inspect("MAIN", "M", "S").pendingApply());
    }

    @ParameterizedTest
    @ValueSource(strings={"prd_goods","prd_store_stock","prd_store_goods","prd_store_price"})
    void draftGoodsAndOldZeroStockRowsBlockBySourceEntityOrStoreIncludingDeleted(String table) {
        jdbc.update("INSERT INTO "+table+" VALUES('OTHER','M','S',1)");
        jdbc.update("INSERT INTO "+table+" VALUES('MAIN','OTHER','OTHER_STORE',1)");
        assertFalse(facts.inspect("MAIN","M","S").hasTrades());
        jdbc.update("INSERT INTO "+table+" VALUES('MAIN','M','SIBLING',1)");
        assertTrue(facts.inspect("MAIN","M","S").hasTrades());
        jdbc.update("DELETE FROM "+table+" WHERE tenant_no='MAIN' AND entity_no='M'");
        jdbc.update("INSERT INTO "+table+" VALUES('MAIN','OLD_ENTITY','S',1)");
        assertTrue(facts.inspect("MAIN","M","S").hasTrades());
    }

    @ParameterizedTest
    @ValueSource(strings={"prd_sku","prd_goods_draft"})
    void orphanSkuAndUnpublishedDraftRetainSourceOwnership(String table) {
        jdbc.update("INSERT INTO "+table+" VALUES('OTHER','M',1)");
        jdbc.update("INSERT INTO "+table+" VALUES('MAIN','OTHER',1)");
        assertFalse(facts.inspect("MAIN","M","S").hasTrades());
        jdbc.update("INSERT INTO "+table+" VALUES('MAIN','M',1)");
        assertTrue(facts.inspect("MAIN","M","S").hasTrades());
    }

    @Test void unavailableHistoryTableFailsInsteadOfReturningClean() {
        jdbc.execute("DROP TABLE ord_after_sale");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> facts.inspect("MAIN", "M", "S"));
    }
    @ParameterizedTest
    @ValueSource(strings={"ord_checkout_operation","ord_checkout_confirmation","ord_ent_service_order","ord_external_certificate_use","pmt_apply","ord_industry_order_source","ord_channel_import","ord_channel_admission_guard"})
    void sourceBusinessIntentsAndConsumptionRecordsAlsoBlock(String table) {
        jdbc.update("INSERT INTO " + table + " VALUES('OTHER','M','S',1)");
        assertFalse(facts.inspect("MAIN","M","S").hasTrades());
        jdbc.update("INSERT INTO " + table + " VALUES('MAIN','M','S',1)");
        assertTrue(facts.inspect("MAIN","M","S").hasTrades());
    }

    @ParameterizedTest
    @ValueSource(strings={"pmt_coupon","pmt_user_coupon","pmt_coupon_issue","mkt_coupon","pmt_pay_coupon_projection","pmt_migration_decision"})
    void entityWideCouponAssetsAndDecisionsCannotBeSilentlyReattributed(String table) {
        jdbc.update("INSERT INTO " + table + " VALUES('MAIN','OTHER',1)");
        jdbc.update("INSERT INTO " + table + " VALUES('OTHER','M',1)");
        assertFalse(facts.inspect("MAIN","M","S").hasTrades());
        jdbc.update("INSERT INTO " + table + " VALUES('MAIN','M',1)");
        assertTrue(facts.inspect("MAIN","M","S").hasTrades());
    }

    @ParameterizedTest
    @ValueSource(strings={"ord_points_binding","ord_points_source_fact"})
    void corePointsJournalsUseTheirExplicitMainOnlyContract(String table) {
        jdbc.update("INSERT INTO " + table + " VALUES('S')");
        assertFalse(facts.inspect("OTHER","M","S").hasTrades());
        assertTrue(facts.inspect("MAIN","M","S").hasTrades());
    }

}
