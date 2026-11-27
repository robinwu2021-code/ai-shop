package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.DecisionReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.HandoverReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.CreateReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.SlotVO;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodIndustryOperationsMapper;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.service.impl.BusinessOperationsServiceImpl;
import ai.neargo.shop.food.service.impl.ServiceSlotServiceImpl;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Real local SQL, conservation, receipt replay, scope and source-evidence tests. Execution is
 * deferred.
 */
public class FoodBusinessOperationsPersistenceTest {
    private JdbcTemplate db;
    private Connection schemaConnection;

    @AfterEach
    void closeSchemaConnection() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
    }

    private TransactionTemplate tx;
    private ServiceSlotServiceImpl slots;
    private BusinessOperationsServiceImpl ops;

    public static Timestamp utc(Integer precision) {
        return Timestamp.valueOf(LocalDateTime.of(2026, 10, 24, 0, 0));
    }

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:biz_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        schemaConnection = ds.getConnection();
        var ddl = new JdbcTemplate(new SingleConnectionDataSource(schemaConnection, true));
        ddl.execute(
                "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR"
                    + " 'ai.neargo.shop.food.scenario.FoodBusinessOperationsPersistenceTest.utc'");
        ddl.execute("CREATE TABLE fnb_close_adjustment (id BIGINT PRIMARY KEY)");
        String migration =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/industry/food/V17__food_service_collaboration_operations.sql"));
        migration =
                migration
                        .replaceAll("(?m)^--.*$", "")
                        .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                        .replace("ENGINE=InnoDB", "")
                        .replace(" JSON ", " VARCHAR(100000) ")
                        .replace(
                                "ADD COLUMN batch_no VARCHAR(32)  NULL,",
                                "ADD COLUMN batch_no VARCHAR(32) NULL; ALTER TABLE"
                                    + " fnb_capacity_claim");
        for (String sql : migration.split(";")) if (!sql.isBlank()) ddl.execute(sql);
        IndustrySchema.create(ds, "ind_store_policy", "ind_assignment");
        db.update("INSERT INTO ind_store_policy (tenant_no,entity_no,policy_no,store_no,industry_code)"
                + " VALUES ('MAIN','E','P','S','FNB')");
        ddl.execute(
                "CREATE TABLE fnb_check (tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no"
                        + " VARCHAR(32),store_no VARCHAR(32),check_no VARCHAR(32),status"
                        + " VARCHAR(16),service_employee_no VARCHAR(32),service_state"
                        + " VARCHAR(16),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,updated_at"
                        + " TIMESTAMP,updated_by VARCHAR(32))");
        db.update(
                "INSERT INTO fnb_check"
                        + " (entity_no,store_no,check_no,status,service_employee_no,service_state)"
                        + " VALUES ('E','S','CHECK','OPEN','A','OPEN')");
        ddl.execute(
                "CREATE TABLE fnb_action_log (id BIGINT AUTO_INCREMENT PRIMARY KEY,entity_no"
                        + " VARCHAR(32),store_no VARCHAR(32),action_no VARCHAR(32),object_type"
                        + " VARCHAR(32),object_no VARCHAR(32),action VARCHAR(64),operator_no"
                        + " VARCHAR(32),reason VARCHAR(255),request_no VARCHAR(64),after_ref"
                        + " VARCHAR(64),created_at TIMESTAMP,updated_at TIMESTAMP,created_by"
                        + " VARCHAR(32),updated_by VARCHAR(32))");
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var mapper = new FoodBusinessOperationsMapper(ds);
        var json = JsonMapper.builder().build();
        slots =
                new ServiceSlotServiceImpl(
                        mapper,
                        new FoodIndustryOperationsMapper(ds),
                        mock(RequestIdempotencyPort.class),
                        Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC),
                        json,
                        manager);
        ops =
                new BusinessOperationsServiceImpl(
                        mapper,
                        new FoodIndustryOperationsMapper(ds),
                        manager,
                        manager,
                        mock(RequestIdempotencyPort.class),
                        json,
                        mock(DiningOrderService.class),
                        mock(DiningReturnService.class),
                        mock(QueueService.class));
    }

    private SlotVO slot() {
        return tx.execute(
                t ->
                        slots.create(
                                "E",
                                "S",
                                "A",
                                new CreateReq(
                                        Instant.parse("2026-10-25T12:00:00Z"),
                                        Instant.parse("2026-10-25T13:00:00Z"),
                                        Instant.parse("2026-10-25T10:00:00Z"),
                                        Instant.parse("2026-10-25T11:00:00Z"),
                                        new BigDecimal("2.500000"),
                                        "PORTION"),
                                "slot"));
    }

    @Test
    void capacityIsConservedAndReleaseDoesNotRepeat() {
        var slot = slot();
        var claim =
                tx.execute(
                        t ->
                                slots.reserve(
                                        "E",
                                        "S",
                                        "A",
                                        new ClaimReq(
                                                slot.slotNo(), "B1", new BigDecimal("2.000000")),
                                        "reserve"));
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        t ->
                                                slots.reserve(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        new ClaimReq(
                                                                slot.slotNo(),
                                                                "B2",
                                                                BigDecimal.ONE),
                                                        "over")))
                .isInstanceOf(BizException.class);
        var released =
                tx.execute(
                        t ->
                                slots.transition(
                                        "E", "S", "A", claim.claimNo(), 0, "RELEASE", "release"));
        var replayed =
                tx.execute(
                        t ->
                                slots.transition(
                                        "E", "S", "A", claim.claimNo(), 0, "RELEASE", "release"));
        assertThat(replayed).isEqualTo(released);
        assertThat(slots.get("E", "S", slot.slotNo()).reserved())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 预占先在业务域提交；调用方订单事务回滚时撤回预占（ADR-068 修订二）。 */
    @Test
    void reservationIsReleasedWhenTheCallingOrderTransactionRollsBack() {
        var slot = slot();
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        t -> {
                                            slots.reserve(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new ClaimReq(slot.slotNo(), "CART-RB", BigDecimal.ONE),
                                                    "reserve-rb");
                                            throw new IllegalStateException("order failed");
                                        }))
                .hasMessage("order failed");
        // 两个句柄同库时预占随订单事务一起回滚；分库时由回滚补偿释放。两种情况都不留下预占。
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fnb_capacity_claim WHERE source_no='CART-RB'"
                                        + " AND status='RESERVED'",
                                Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT reserved_qty FROM fnb_service_slot", BigDecimal.class))
                .isZero();
    }

    @Test
    void permanentCommandCannotChangeContentAndScopeCannotReadOtherStore() {
        var slot = slot();
        assertThat(slot().slotNo()).isEqualTo(slot.slotNo());
        assertThatThrownBy(() -> slots.get("E", "FOREIGN", slot.slotNo()))
                .isInstanceOf(BizException.class);
        var req = new ClaimReq(slot.slotNo(), "B1", BigDecimal.ONE);
        tx.execute(t -> slots.reserve("E", "S", "A", req, "reserve"));
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        t ->
                                                slots.reserve(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        new ClaimReq(
                                                                slot.slotNo(),
                                                                "B1",
                                                                new BigDecimal("2")),
                                                        "reserve")))
                .isInstanceOf(BizException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM fnb_capacity_claim", Integer.class))
                .isEqualTo(1);
    }

    /** 业务键回查（TDD-餐饮域迁移新库 §8-Q1）：交接单号由请求键派生，不靠永久命令回执。 */
    @Test
    void handoverReplaysByDerivedNumberAndRejectsSameKeyWithOtherContent() {
        var req = new HandoverReq("B", List.of(new Source("CHECK", "CHECK")), "next employee");
        var first = ops.propose("E", "S", "A", req, "handover-replay");
        assertThat(ops.propose("E", "S", "A", req, "handover-replay")).isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                ops.propose(
                                        "E",
                                        "S",
                                        "A",
                                        new HandoverReq(
                                                "B",
                                                List.of(new Source("CHECK", "CHECK")),
                                                "other reason"),
                                        "handover-replay"))
                .isInstanceOf(BizException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(DISTINCT handover_no) FROM ind_assignment", Integer.class))
                .isEqualTo(1);
        var accept = new DecisionReq(0, "accept");
        var accepted = ops.accept("E", "S", "B", first.handoverNo(), accept, "accept-1");
        assertThat(ops.accept("E", "S", "B", first.handoverNo(), accept, "accept-2"))
                .isEqualTo(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM ind_assignment WHERE status='ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    /** 合并 ⑦（TDD §2.5）：再次交接接收后，上一任责任结束，新责任指向它；取消只动待接收的条。 */
    @Test
    void chainedHandoverEndsPreviousResponsibilityAndCancelLeavesItUntouched() {
        var source = List.of(new Source("CHECK", "CHECK"));
        var first = ops.propose("E", "S", "A", new HandoverReq("B", source, "to B"), "h1");
        ops.accept("E", "S", "B", first.handoverNo(), new DecisionReq(0, "ok"), "a1");
        var second = ops.propose("E", "S", "B", new HandoverReq("C", source, "to C"), "h2");
        // 发起人不是当前负责人的交接被拒。
        assertThatThrownBy(() -> ops.propose("E", "S", "A", new HandoverReq("C", source, "stale"), "h3"))
                .isInstanceOf(BizException.class);
        var cancelled = ops.cancel("E", "S", "B", second.handoverNo(), new DecisionReq(0, "no"), "c2");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(ops.evidence("E", "S", new Source("CHECK", "CHECK")).currentOwner()).isEqualTo("B");
        var third = ops.propose("E", "S", "B", new HandoverReq("C", source, "to C again"), "h4");
        ops.accept("E", "S", "C", third.handoverNo(), new DecisionReq(0, "ok"), "a3");
        assertThat(ops.evidence("E", "S", new Source("CHECK", "CHECK")).currentOwner()).isEqualTo("C");
        assertThat(db.queryForList(
                        "SELECT personnel_no FROM ind_assignment WHERE status='ACTIVE' AND ended_at IS NULL",
                        String.class))
                .containsExactly("C");
        String endedB = db.queryForObject(
                "SELECT assignment_no FROM ind_assignment WHERE handover_no=? AND status='ENDED'", String.class,
                first.handoverNo());
        assertThat(db.queryForObject(
                        "SELECT previous_assignment_no FROM ind_assignment WHERE handover_no=?", String.class,
                        third.handoverNo()))
                .isEqualTo(endedB);
        assertThat(db.queryForObject(
                        "SELECT status FROM ind_assignment WHERE handover_no=?", String.class, second.handoverNo()))
                .isEqualTo("CANCELLED");
    }

    @Test
    void onlyReceiverAcceptsAndOriginalBusinessStateIsNotClosed() {
        var req = new HandoverReq("B", List.of(new Source("CHECK", "CHECK")), "next employee");
        var handover = ops.propose("E", "S", "A", req, "handover");
        assertThatThrownBy(
                        () ->
                                ops.accept(
                                        "E",
                                        "S",
                                        "A",
                                        handover.handoverNo(),
                                        new DecisionReq(0, "accept"),
                                        "wrong"))
                .isInstanceOf(BizException.class);
        var accepted =
                ops.accept(
                        "E",
                        "S",
                        "B",
                        handover.handoverNo(),
                        new DecisionReq(0, "accept"),
                        "accept");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(ops.evidence("E", "S", new Source("CHECK", "CHECK")).currentOwner())
                .isEqualTo("B");
        assertThat(db.queryForObject("SELECT status FROM fnb_check", String.class))
                .isEqualTo("OPEN");
    }

    @Test
    void changedSourceCannotBeAcceptedAndCaseResolutionNeedsOwnerTerminalProof() {
        var handover =
                ops.propose(
                        "E",
                        "S",
                        "A",
                        new HandoverReq("B", List.of(new Source("CHECK", "CHECK")), "handover"),
                        "handover");
        db.update("UPDATE fnb_check SET version=version+1");
        assertThatThrownBy(
                        () ->
                                ops.accept(
                                        "E",
                                        "S",
                                        "B",
                                        handover.handoverNo(),
                                        new DecisionReq(0, "accept"),
                                        "accept"))
                .isInstanceOf(BizException.class);
        var work =
                ops.openCase(
                        "E",
                        "S",
                        "A",
                        new CaseReq(new Source("CHECK", "CHECK"), "A", "service pending"),
                        "case");
        assertThatThrownBy(
                        () ->
                                ops.resolveCase(
                                        "E",
                                        "S",
                                        "A",
                                        work.caseNo(),
                                        new DecisionReq(0, "done"),
                                        "resolve"))
                .isInstanceOf(BizException.class);
        db.update("UPDATE fnb_check SET status='VOIDED',version=version+1");
        assertThat(
                        ops.resolveCase(
                                        "E",
                                        "S",
                                        "A",
                                        work.caseNo(),
                                        new DecisionReq(0, "owner verified"),
                                        "resolve")
                                .resolutionHash())
                .isNotBlank();
    }
}
