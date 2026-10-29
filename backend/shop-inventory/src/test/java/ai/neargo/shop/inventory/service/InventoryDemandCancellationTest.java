package ai.neargo.shop.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.inventory.domain.mapper.InventoryDemandPlanMapper;
import ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.StockPostingService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

class InventoryDemandCancellationTest {
    @ParameterizedTest
    @ValueSource(strings = {"HELD", "ACCEPTED_UNPAID"})
    void fullReleaseTerminatesOnlyUnacceptedHoldAndReplaysOriginalReceipt(String initial) {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:cancel_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE inv_owner(owner_id VARCHAR(32),status VARCHAR(16))");
        db.execute(
                "CREATE TABLE inv_stock_balance(owner_id VARCHAR(32),item_id VARCHAR(32),"
                        + "location_id VARCHAR(32),reserved DECIMAL(24,6),version BIGINT)");
        db.execute(
                "CREATE TABLE inv_demand_plan(id BIGINT,owner_id VARCHAR(32),external_ref"
                    + " VARCHAR(64),plan_no VARCHAR(32),plan_hash VARCHAR(64),lifecycle_version"
                    + " INT,status VARCHAR(24),expires_at TIMESTAMP)");
        db.execute(
                "CREATE TABLE inv_demand_leaf(id BIGINT,owner_id VARCHAR(32),plan_no"
                    + " VARCHAR(32),leaf_ref VARCHAR(64),component_ref VARCHAR(64),fulfillment_ref"
                    + " VARCHAR(64),branch_kind VARCHAR(24),item_id VARCHAR(32),location_id"
                    + " VARCHAR(32),qty DECIMAL(24,6),issued_qty DECIMAL(24,6),released_qty"
                    + " DECIMAL(24,6),unit VARCHAR(16),definition_hash VARCHAR(64),document_no"
                    + " VARCHAR(32),ledger_ref VARCHAR(32),cost_minor BIGINT,currency VARCHAR(3))");
        db.execute(
                "CREATE TABLE inv_demand_operation(owner_id VARCHAR(32),plan_no"
                    + " VARCHAR(32),operation_ref VARCHAR(64),request_hash VARCHAR(64),kind"
                    + " VARCHAR(24),receipt_snapshot TEXT,delta_snapshot TEXT,created_by"
                    + " VARCHAR(64),reversed INT DEFAULT 0)");
        String hash = "a".repeat(64);
        db.update("INSERT INTO inv_owner VALUES('OWNER','ACTIVE')");
        db.update("INSERT INTO inv_stock_balance VALUES('OWNER','I','LOCATION',0.125,0)");
        db.update(
                "INSERT INTO inv_demand_plan"
                    + " VALUES(1,'OWNER','ORDER','PLAN',?,3,?,DATEADD('HOUR',1,CURRENT_TIMESTAMP))",
                hash,
                initial);
        db.update(
                "INSERT INTO inv_demand_leaf VALUES(1,'OWNER','PLAN','L','C','F','RECIPE_MATERIAL',"
                        + "'I','LOCATION',0.125,0,0,'KG',?,NULL,NULL,NULL,NULL)",
                hash);
        var acl = mock(InventoryAclService.class);
        when(acl.findOwnerId("E")).thenReturn("OWNER");
        var service =
                new InventoryDemandPlanPortImpl(
                        new InventoryDemandPlanMapper(ds),
                        acl,
                        mock(MaterialCatalogQueryPort.class),
                        mock(StockPostingService.class),
                        new JsonMapper());
        var scope = new Scope("MAIN", "E", "ORDER");
        var targets = List.of(new Target("L", new BigDecimal("0.125")));
        var result = service.releaseTo(scope, "CANCEL_ORDER", hash, targets);
        assertThat(result.status()).isEqualTo("HELD".equals(initial) ? "RELEASED" : initial);
        assertThat(result.lines().getFirst().releasedQty()).isEqualByComparingTo("0.125");
        assertThat(service.releaseTo(scope, "CANCEL_ORDER", hash, targets)).isEqualTo(result);
        assertThat(db.queryForObject("SELECT reserved FROM inv_stock_balance", BigDecimal.class))
                .isZero();
        assertThat(db.queryForObject("SELECT version FROM inv_stock_balance", Long.class)).isOne();
    }
}
