package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.service.impl.CheckBusinessGuardServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

public class CheckAmendmentGuardTest {
    private JdbcTemplate db;
    private CheckBusinessGuardServiceImpl guard;

    public static Timestamp utc(Integer ignored) {
        return Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 0, 0));
    }

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:guard_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE ALIAS UTC_TIMESTAMP FOR"
                    + " 'ai.neargo.shop.food.scenario.CheckAmendmentGuardTest.utc'");
        db.execute(
                "CREATE TABLE fnb_order_batch(tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),batch_no VARCHAR(32),check_no VARCHAR(32),deleted INT)");
        db.execute(
                "CREATE TABLE fnb_order_line(tenant_no VARCHAR(32),entity_no VARCHAR(32),batch_no"
                    + " VARCHAR(32),food_line_no VARCHAR(32),deleted INT)");
        db.execute(
                "CREATE TABLE fnb_check_line_membership(tenant_no VARCHAR(32),entity_no"
                    + " VARCHAR(32),batch_no VARCHAR(32),active_line_no VARCHAR(32),check_no"
                    + " VARCHAR(32),deleted INT)");
        db.execute(
                "CREATE TABLE fnb_check_batch_membership(tenant_no VARCHAR(32),entity_no"
                    + " VARCHAR(32),batch_no VARCHAR(32),check_no VARCHAR(32),deleted"
                    + " INT,detached_at TIMESTAMP)");
        db.execute(
                "CREATE TABLE fnb_return(tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),batch_no VARCHAR(32),deleted INT,status VARCHAR(32))");
        db.execute(
                "CREATE TABLE fnb_check(tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),check_no VARCHAR(32),deleted INT,status"
                    + " VARCHAR(16),service_state VARCHAR(16))");
        db.execute(
                "CREATE TABLE fnb_check_freeze(entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),active_check_no VARCHAR(32))");
        db.execute(
                "CREATE TABLE fnb_settlement_item(entity_no VARCHAR(32),settlement_no"
                    + " VARCHAR(32),payment_root_no VARCHAR(32),check_no VARCHAR(32),deleted INT)");
        db.execute(
                "CREATE TABLE fnb_settlement(entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),settlement_no VARCHAR(32),status VARCHAR(16),deleted INT)");
        db.execute(
                "CREATE TABLE fnb_core_order_binding(entity_no VARCHAR(32),batch_no"
                    + " VARCHAR(32),payment_root_no VARCHAR(32))");
        db.execute(
                "CREATE TABLE fnb_business_share_plan(entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),active_check_no VARCHAR(32),status"
                    + " VARCHAR(32),cancellation_reason VARCHAR(255),updated_by"
                    + " VARCHAR(32),updated_at TIMESTAMP,version BIGINT)");
        db.update("INSERT INTO fnb_order_batch VALUES('MAIN','E','S','B','OLD',0)");
        db.update("INSERT INTO fnb_order_line VALUES('MAIN','E','B','L',0)");
        db.update("INSERT INTO fnb_check_line_membership VALUES('MAIN','E','B','L','NEW',0)");
        db.update(
                "INSERT INTO fnb_check"
                    + " VALUES('MAIN','E','S','OLD',0,'CLOSED','CLOSED'),('MAIN','E','S','NEW',0,'OPEN','OPEN')");
        guard = new CheckBusinessGuardServiceImpl(new FoodBusinessOperationsMapper(ds));
    }

    @Test
    void movedLineUsesCurrentOwnerFreezeAndInvalidatesItsAa() {
        assertThatCode(() -> guard.lockBatchChecks("E", "S", "B")).doesNotThrowAnyException();
        db.update("INSERT INTO fnb_check_freeze VALUES('E','S','NEW')");
        assertThatThrownBy(() -> guard.lockBatchChecks("E", "S", "B"))
                .isInstanceOf(BizException.class);
        db.update("DELETE FROM fnb_check_freeze");
        db.update(
                "INSERT INTO fnb_business_share_plan"
                    + " VALUES('E','S','NEW','CONFIRMED_BUSINESS_ONLY',NULL,NULL,NULL,0)");
        guard.invalidateBatchShares("E", "S", "B", "A", "RETURN_REQUEST");
        assertThat(db.queryForObject("SELECT status FROM fnb_business_share_plan", String.class))
                .isEqualTo("CANCELLED_BUSINESS_ONLY");
    }

    @Test
    void pendingAmendmentBlocksOnlyCurrentOwnerFreezeUntilConfirmed() {
        for (String status :
                new String[] {"BUSINESS_REQUESTED", "APPROVAL_PENDING", "APPROVAL_UNKNOWN"}) {
            db.update("DELETE FROM fnb_return");
            db.update("INSERT INTO fnb_return VALUES('MAIN','E','S','B',0,?)", status);
            assertThatThrownBy(() -> guard.assertNoPendingChanges("E", "S", "NEW"))
                    .isInstanceOf(BizException.class);
            assertThatCode(() -> guard.assertNoPendingChanges("E", "S", "OLD"))
                    .doesNotThrowAnyException();
        }
        db.update("UPDATE fnb_return SET status='APPROVED'");
        assertThatCode(() -> guard.assertNoPendingChanges("E", "S", "NEW"))
                .doesNotThrowAnyException();
    }
}
