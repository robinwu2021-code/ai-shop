package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.mapper.FoodIndustryOperationsMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 合并 ⑤（TDD-餐饮域迁移新库 §2.5）：定时出品登记为出品单的 {@code SCHEDULED} 状态，
 * {@code next_attempt_at} 非空即待下达；认领与完结按它做比较交换，不加出品单版本号。
 */
class ScheduledKitchenDispatchMapperTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 27, 12, 0);
    private JdbcTemplate sql;
    private FoodIndustryOperationsMapper industry;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:scheduled_dispatch_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        sql = new JdbcTemplate(source);
        sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'ai.neargo.shop.food.scenario.ServiceEntitlementPersistenceTest.utc'");
        IndustrySchema.create(source, "fnb_kitchen_ticket");
        industry = new FoodIndustryOperationsMapper(source);
    }

    @Test
    void registersOnceAsScheduledTicketAndIsDueOnlyAfterDispatchTime() {
        industry.createScheduledDispatch("E", "S", "B1", AT, "actor");
        industry.createScheduledDispatch("E", "S", "B1", AT, "SYSTEM");
        var row = sql.queryForMap("SELECT tenant_no,store_no,kind,status,dispatch_key,dispatch_attempts,version"
                + " FROM fnb_kitchen_ticket");
        assertThat(row).containsEntry("tenant_no", "MAIN").containsEntry("store_no", "S")
                .containsEntry("kind", "MAKE").containsEntry("status", "SCHEDULED")
                .containsEntry("dispatch_key", FoodIndustryOperationsMapper.kitchenDispatchKey("E", "S", "B1"));
        assertThat(industry.dueScheduledDispatch(AT.minusSeconds(1), 10)).isEmpty();
        var due = industry.dueScheduledDispatch(AT, 10);
        assertThat(due).hasSize(1);
        assertThat(due.getFirst().text("batch_no")).isEqualTo("B1");
    }

    @Test
    void claimIsCompareAndSetAndRetryThenCompleteClearsPendingWithoutTouchingVersion() {
        industry.createScheduledDispatch("E", "S", "B1", AT, "actor");
        String ticket = sql.queryForObject("SELECT ticket_no FROM fnb_kitchen_ticket", String.class);
        var lease = AT.plusMinutes(2);
        assertThat(industry.claimScheduledDispatch("E", ticket, AT, lease)).isOne();
        // 同一时刻的第二个认领者读到的是旧值，抢不到。
        assertThat(industry.claimScheduledDispatch("E", ticket, AT, lease)).isZero();
        assertThat(industry.dueScheduledDispatch(AT.plusMinutes(1), 10)).isEmpty();

        var retryAt = AT.plusMinutes(4);
        assertThat(industry.retryScheduledDispatch("E", ticket, lease, "IllegalStateException", retryAt)).isOne();
        assertThat(sql.queryForObject("SELECT last_error FROM fnb_kitchen_ticket", String.class))
                .isEqualTo("IllegalStateException");
        assertThat(industry.dueScheduledDispatch(retryAt, 10)).hasSize(1);

        var second = retryAt.plusMinutes(2);
        assertThat(industry.claimScheduledDispatch("E", ticket, retryAt, second)).isOne();
        // 建单把状态改成排队、加了版本号（模拟 KitchenTaskServiceImpl#accepted）：完结不受版本影响。
        sql.update("UPDATE fnb_kitchen_ticket SET status='QUEUED',version=version+1");
        assertThat(industry.completeScheduledDispatch("E", ticket, second)).isOne();
        var row = sql.queryForMap("SELECT status,next_attempt_at,last_error,dispatch_attempts,version"
                + " FROM fnb_kitchen_ticket");
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(((Number) row.get("dispatch_attempts")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("version")).longValue()).isOne();
        assertThat(industry.dueScheduledDispatch(second.plusDays(1), 10)).isEmpty();
        // 已下达的批次再登记（补建）不会多出一张单。
        industry.createScheduledDispatch("E", "S", "B1", AT, "SYSTEM");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_kitchen_ticket", Integer.class)).isOne();
    }

    @Test
    void cancelledBatchClosesOnlyAnUndispatchedTicket() {
        industry.createScheduledDispatch("E", "S", "B1", AT, "actor");
        String ticket = sql.queryForObject("SELECT ticket_no FROM fnb_kitchen_ticket", String.class);
        var lease = AT.plusMinutes(2);
        industry.claimScheduledDispatch("E", ticket, AT, lease);
        assertThat(industry.cancelScheduledDispatch("E", ticket, lease)).isOne();
        assertThat(sql.queryForObject("SELECT status FROM fnb_kitchen_ticket", String.class)).isEqualTo("CLOSED");
        assertThat(industry.dueScheduledDispatch(AT.plusDays(1), 10)).isEmpty();
    }
}
