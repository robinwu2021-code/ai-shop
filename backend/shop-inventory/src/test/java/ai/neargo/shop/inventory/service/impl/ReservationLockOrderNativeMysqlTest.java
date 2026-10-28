package ai.neargo.shop.inventory.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.inventory.config.InventoryDataSourceConfig;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionClaimMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LocationMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.SupplierMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers;
import ai.neargo.shop.inventory.mapper.RefundRestoreMapper;
import ai.neargo.shop.inventory.mapper.ReturnReceiptMapper;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionGuard;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort.CurrentState;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in native regression against an externally provisioned inventory V14 database.
 * Supply inventory.lock.test.native=true and inventory.lock.test.url/user/password.
 * Real inventory configuration, mappers, services, posting and two transactions are used;
 * this fixture never runs Flyway. The query interceptor only schedules competing callers.
 * Reinstating LINE-before-HEAD makes the forced observer/writer schedule deadlock.
 */
@EnabledIfSystemProperty(named = "inventory.lock.test.native", matches = "true")
class ReservationLockOrderNativeMysqlTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ReservationServiceImpl reservations;
    private ReservationDispositionServiceImpl dispositions;
    private StockPostingServiceImpl posting;
    private OutboundServiceImpl outbound;
    private ExecutionStockRetentionGuard retention;
    private final LockSchedule schedule = new LockSchedule();
    private final String id = "c04" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final List<String> seededOwners = new ArrayList<>();
    private Original original;
    private String operation;

    @BeforeEach
    void connectToAlreadyMigratedNativeInventory() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("native-inventory", Map.of(
                "shop.inventory.enabled", "true",
                "shop.inventory.flyway-enabled", "false",
                "shop.inventory.datasource.url", System.getProperty("inventory.lock.test.url"),
                "shop.inventory.datasource.username", System.getProperty("inventory.lock.test.user", "root"),
                "shop.inventory.datasource.password", System.getProperty("inventory.lock.test.password", ""))));
        context.register(InventoryDataSourceConfig.class);
        context.refresh();
        var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
        assertThat(configuration.getDatabaseId()).isEqualTo("MySQL");
        configuration.addInterceptor(schedule);
        jdbc = new JdbcTemplate(context.getBean("invDataSource", DataSource.class));
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).doesNotContainIgnoringCase("h2");
        tx = new TransactionTemplate(context.getBean("invTransactionManager", PlatformTransactionManager.class));
        tx.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        tx.setTimeout(15);
        var balances = mapper(BalanceMapper.class);
        retention = new ExecutionStockRetentionGuard(mapper(ExecutionRetentionMapper.class),
                mapper(ExecutionRetentionClaimMapper.class), new ExecutionStockRetentionSchemaVerifier(
                        context.getBean("invDataSource", DataSource.class), "inv_flyway_history", null),
                mapper(ReservationMapper.class), mapper(ReservationLineMapper.class), mapper(OutboundOrderMapper.class),
                mapper(RefundRestoreMapper.class), mapper(ReturnReceiptMapper.class));
        posting = new StockPostingServiceImpl(balances, mapper(LedgerMapper.class), mapper(OutboxMapper.class),
                retention);
        var guard = new InventoryMutationGuard(mapper(StockCountMapper.class), mapper(StockCountLineMapper.class),
                mapper(LocationMapper.class));
        outbound = new OutboundServiceImpl(mapper(OutboundOrderMapper.class), mapper(OutboundLineMapper.class),
                mapper(ItemMapper.class), mapper(SupplierMapper.class), posting, guard, retention);
        var inbound = new InboundServiceImpl(mapper(InboundOrderMapper.class), mapper(InboundLineMapper.class),
                mapper(ItemMapper.class), posting, guard);
        reservations = new ReservationServiceImpl(mapper(ReservationMapper.class), mapper(ReservationLineMapper.class),
                balances, outbound, inbound, retention);
        dispositions = new ReservationDispositionServiceImpl(mapper(ReservationMapper.class),
                mapper(ReservationLineMapper.class), mapper(OwnerMapper.class), balances,
                mapper(OutboundOrderMapper.class), mapper(OutboundLineMapper.class), reservations,
                new ObjectMapper(), retention);
    }

    @AfterEach
    void removeOnlyThisTestsRows() {
        if (jdbc != null) {
            // All business identifiers are unique to this test; no shared seeds are modified.
            for (String owner : seededOwners) {
                for (String table : List.of("inv_outbound_line", "inv_outbound_order", "inv_ledger", "inv_outbox",
                        "inv_reservation_line", "inv_reservation", "inv_stock_balance", "inv_location", "inv_owner")) {
                    jdbc.update("DELETE FROM " + table + " WHERE owner_id=?", owner);
                }
            }
        }
        if (context != null) context.close();
    }

    @Test
    void originalHeadCurrentReadUsesCanonicalIndexWithoutFilesort() {
        seed("ALLOCATED", true);
        var statement = context.getBean(SqlSessionFactory.class).getConfiguration()
                .getMappedStatement(InventoryMappers.ReservationMapper.class.getName() + ".lockOriginal");
        var sql = statement.getBoundSql(Map.of("order", id)).getSql();
        var plan = jdbc.queryForList("EXPLAIN " + sql, id);
        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(row -> {
            assertThat(row.get("key")).isEqualTo("uk_reservation");
            assertThat(String.valueOf(row.get("Extra"))).doesNotContainIgnoringCase("filesort");
        });
    }

    @Test
    void fulfillmentCurrentJoinDrivesCanonicalHeadIndexBeforeLineLookupWithoutFilesort() {
        seed("ALLOCATED", true);
        seedRow(id + "b", "ALLOCATED", true);
        var statement = context.getBean(SqlSessionFactory.class).getConfiguration()
                .getMappedStatement(InventoryMappers.ReservationMapper.class.getName() + ".lockByFulfillment");
        var sql = statement.getBoundSql(Map.of("fulfillment", id)).getSql();
        var plan = jdbc.queryForList("EXPLAIN " + sql, id);
        assertThat(plan).hasSize(2);
        assertThat(plan.getFirst()).containsEntry("table", "r").containsEntry("key", "uk_reservation");
        assertThat(plan.getLast()).containsEntry("table", "l").containsEntry("key", "uk_res_line");
        assertThat(plan).allSatisfy(row -> assertThat(String.valueOf(row.get("Extra")))
                .doesNotContainIgnoringCase("filesort").doesNotContainIgnoringCase("join buffer"));
    }

    @Test
    void originalHeadLockSeesNewCommittedHeadAfterEarlierRepeatableReadSnapshot() {
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_reservation WHERE external_ref=?",
                    Long.class, id)).isZero();
            try (var otherConnection = Executors.newSingleThreadExecutor()) {
                // Seed commits on a different connection after this transaction's snapshot.
                otherConnection.submit(() -> seed("ALLOCATED", true)).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("Independent original creation failed", failure);
            }
            var heads = mapper(ReservationMapper.class).lockOriginal(id);
            assertThat(heads).hasSize(1);
            assertThat(heads.getFirst().getReservationId()).isEqualTo(id);
            assertThat(heads.getFirst().getDispositionRevision()).isEqualTo(1L);
        });
    }

    @Test
    void originalObserverAndFulfillmentIssueCompleteWithoutHeadLineDeadlock() throws Exception {
        seed("ALLOCATED", true);
        var before = tx.execute(status -> dispositions.observeOriginal(new Lookup(original, operation)));
        race(() -> {
            var observed = dispositions.observeOriginal(new Lookup(original, operation));
            assertThat(observed.state()).isEqualTo(CurrentState.ALLOCATED);
            assertThat(observed.observationHash()).isEqualTo(before.observationHash());
        }, () -> assertThat(reservations.issueByFulfillmentRef(id, "C04_TEST")).isNotBlank());
        assertTerminal("COMMITTED", 0, 1, 0, 0);
        var consumed = tx.execute(status -> dispositions.observeOriginal(new Lookup(original, operation)));
        assertThat(consumed.state()).isEqualTo(CurrentState.CONSUMED);
        String hash = consumed.observationHash();
        tx.executeWithoutResult(status -> reservations.issueByFulfillmentRef(id, "C04_TEST"));
        assertThat(tx.execute(status -> dispositions.observeOriginal(new Lookup(original, operation)))
                .observationHash()).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_ledger WHERE owner_id=?", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    void originalObserverAndFulfillmentReleaseCompleteWithoutHeadLineDeadlock() throws Exception {
        seed("ALLOCATED", true);
        race(() -> assertThat(dispositions.observeOriginal(new Lookup(original, operation)).state())
                .isEqualTo(CurrentState.ALLOCATED), () -> reservations.releaseAllocatedByFulfillmentRef(id));
        assertTerminal("RELEASED", 1, 0, 1, 0);
        tx.executeWithoutResult(status -> reservations.releaseAllocatedByFulfillmentRef(id));
        assertTerminal("RELEASED", 1, 0, 1, 0);
    }

    @Test
    void originalReleaseAndFulfillmentIssueSerializeWithoutConsumingHeldStock() throws Exception {
        seed("HELD", false);
        var lookup = new ReservationLookup(original, StockDispositionContract.reservationOperationNo(original));
        race(() -> assertThat(dispositions.releaseOriginal(lookup).outcome()).isEqualTo("RELEASED"),
                () -> assertThat(reservations.issueByFulfillmentRef(id, "C04_TEST")).isNull());
        assertTerminal("RELEASED", 1, 0, 1, 0);
        assertThat(tx.execute(status -> dispositions.releaseOriginal(lookup)).outcome()).isEqualTo("RELEASED");
    }

    @Test
    void fulfillmentIssueSeesBothNewCommittedHeadsAfterEarlierRepeatableReadSnapshot() {
        assertFulfillmentAfterEarlierSnapshot(true);
    }

    @Test
    void fulfillmentReleaseSeesBothNewCommittedHeadsAfterEarlierRepeatableReadSnapshot() {
        assertFulfillmentAfterEarlierSnapshot(false);
    }

    @Test
    void fulfillmentIssuePreservesNativeCaseInsensitiveReferenceEquality() {
        seed("ALLOCATED", true);
        tx.executeWithoutResult(status -> reservations.issueByFulfillmentRef(id.toUpperCase(Locale.ROOT), "C04_TEST"));
        assertTerminal("COMMITTED", 0, 1, 0, 0);
    }

    @Test
    void fulfillmentReleasePreservesNativeCaseInsensitiveReferenceEquality() {
        seed("ALLOCATED", true);
        tx.executeWithoutResult(status -> reservations.releaseAllocatedByFulfillmentRef(id.toUpperCase(Locale.ROOT)));
        assertTerminal("RELEASED", 1, 0, 1, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVERSE", "VOID"})
    void reversalAndVoidTakeAllCurrentReservationRootsAndLinesBeforeAnyOutboundLock(String action) {
        seed("ALLOCATED", true);
        String document = tx.execute(status -> reservations.issueByFulfillmentRef(id, "C04_TEST"));
        var trace = new ArrayList<String>();
        schedule.trace.set(trace);
        try {
            tx.executeWithoutResult(status -> {
                if ("VOID".equals(action)) {
                    outbound.voidOrder(id, document, "C04_TEST");
                } else {
                    posting.reverse(id, document, "OUT", "C04_TEST");
                }
            });
        } finally {
            schedule.trace.remove();
        }
        int firstOutboundLock = -1;
        int lastSourceLineLock = -1;
        for (int i = 0; i < trace.size(); i++) {
            String sql = trace.get(i);
            if (sql.contains("INV_OUTBOUND_ORDER") && sql.contains("FOR UPDATE") && firstOutboundLock < 0) {
                firstOutboundLock = i;
            }
            if (sql.contains("FROM INV_RESERVATION_LINE") && sql.contains("FOR UPDATE")) {
                lastSourceLineLock = i;
            }
        }
        assertThat(firstOutboundLock).isGreaterThan(lastSourceLineLock);
        assertThat(lastSourceLineLock).isGreaterThanOrEqualTo(0);
        assertThat(trace).noneMatch(sql -> sql.contains("JOIN INV_OUTBOUND_ORDER") && sql.contains("FOR UPDATE"));
    }

    @Test
    void validatedOutboundHandleAvoidsNestedRootScansButExpiresAtTheInventoryTransactionBoundary() {
        seed("ALLOCATED", true);
        String document = tx.execute(status -> reservations.issueByFulfillmentRef(id, "C04_TEST"));
        var trace = new ArrayList<String>();
        schedule.trace.set(trace);
        try {
            tx.executeWithoutResult(status -> {
                assertThat(retention.lockReversibleOutbound(id, document)).isNotNull();
                assertThat(retention.lockReversibleOutbound(id, document)).isNotNull();
            });
            tx.executeWithoutResult(status -> assertThat(retention.lockReversibleOutbound(id, document)).isNotNull());
        } finally {
            schedule.trace.remove();
        }
        assertThat(trace.stream().filter(sql -> sql.contains("FROM INV_RESERVATION ")
                && sql.contains("FOR UPDATE")).count()).isEqualTo(2);
        assertThat(trace.stream().filter(sql -> sql.contains("FROM INV_OUTBOUND_ORDER ")
                && sql.contains("FOR UPDATE")).count()).isEqualTo(2);
        assertThatThrownBy(() -> retention.lockReversibleOutbound(id, document))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transactionBoundHandleCannotBeReinterpretedByMutatingItsReturnedAssociation() {
        seed("ALLOCATED", true);
        String document = tx.execute(status -> reservations.issueByFulfillmentRef(id, "C04_TEST"));
        tx.executeWithoutResult(status -> {
            var locked = retention.lockReversibleOutbound(id, document);
            locked.setSourceRef(id + "different");
            assertThatThrownBy(() -> retention.lockReversibleOutbound(id, document))
                    .isInstanceOf(RuntimeException.class);
        });
        assertThat(jdbc.queryForObject("SELECT source_ref FROM inv_outbound_order WHERE outbound_no=?",
                String.class, document)).isEqualTo(id);
    }

    @Test
    void inheritedRepeatableReadMissingOutboundDiscoveryCannotReverseALaterCommittedSale() {
        seed("ALLOCATED", true);
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_outbound_order WHERE source_ref=?",
                    Long.class, id)).isZero();
            String document;
            try (var worker = Executors.newSingleThreadExecutor()) {
                document = worker.submit(() -> tx.execute(ignored -> reservations.issueByFulfillmentRef(id,
                        "C04_TEST"))).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertThatThrownBy(() -> posting.reverse(id, document, "OUT", "C04_TEST"))
                    .isInstanceOf(RuntimeException.class);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_ledger WHERE owner_id=? AND doc_no LIKE '%-R'",
                Long.class, id)).isZero();
    }

    @Test
    void inheritedRepeatableReadStaleAssociationAbortsBeforeAnyReverseEffect() {
        seed("ALLOCATED", true);
        String document = tx.execute(status -> reservations.issueByFulfillmentRef(id, "C04_TEST"));
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT source_ref FROM inv_outbound_order WHERE outbound_no=?",
                    String.class, document)).isEqualTo(id);
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> jdbc.update("UPDATE inv_outbound_order SET source_ref=? WHERE outbound_no=?",
                        id + "changed", document)).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertThatThrownBy(() -> posting.reverse(id, document, "OUT", "C04_TEST"))
                    .isInstanceOf(RuntimeException.class);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_ledger WHERE owner_id=? AND doc_no=?",
                Long.class, id, document + "-R")).isZero();
    }

    private void assertFulfillmentAfterEarlierSnapshot(boolean issue) {
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_reservation_line WHERE fulfillment_ref=?",
                    Long.class, id)).isZero();
            try (var otherConnection = Executors.newSingleThreadExecutor()) {
                otherConnection.submit(() -> {
                    // Reverse physical insertion order exercises canonical acquisition across roots.
                    seedRow(id + "b", "ALLOCATED", true);
                    seed("ALLOCATED", true);
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("Independent fulfillment creation failed", failure);
            }
            if (issue) assertThat(reservations.issueByFulfillmentRef(id, "C04_TEST")).isNotBlank();
            else reservations.releaseAllocatedByFulfillmentRef(id);
        });
        assertTerminal(issue ? "COMMITTED" : "RELEASED", issue ? 0 : 1, issue ? 1 : 0, issue ? 0 : 1, 0);
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved FROM inv_stock_balance WHERE owner_id=?", id + "b"))
                .containsEntry("on_hand", issue ? 0 : 1).containsEntry("reserved", 0);
        assertThat(jdbc.queryForMap("SELECT issued_qty,released_qty FROM inv_reservation_line WHERE owner_id=?", id + "b"))
                .containsEntry("issued_qty", issue ? 1 : 0).containsEntry("released_qty", issue ? 0 : 1);
        assertThat(jdbc.queryForObject("SELECT disposition_revision FROM inv_reservation WHERE owner_id=?",
                Long.class, id + "b")).isEqualTo(2L);
    }

    private void seed(String state, boolean confirmed) {
        original = seedRow(id, state, confirmed);
        operation = StockDispositionContract.operationNo(original);
    }

    private Original seedRow(String rowId, String state, boolean confirmed) {
        seededOwners.add(rowId);
        var needs = List.of(new Need(rowId, null, id, 1));
        var source = new Original("MAIN", rowId, id, "INVENTORY", 2,
                StockDispositionContract.manifestHash(needs), needs);
        jdbc.update("INSERT INTO inv_owner(owner_id,name,external_ref) VALUES(?,?,?)", rowId, rowId, rowId);
        jdbc.update("INSERT INTO inv_location(location_id,owner_id,name) VALUES(?,?,?)", rowId, rowId, rowId);
        jdbc.update("INSERT INTO inv_stock_balance(owner_id,item_id,location_id,on_hand,reserved) VALUES(?,?,?,1,1)",
                rowId, rowId, rowId);
        jdbc.update("INSERT INTO inv_reservation(reservation_id,owner_id,external_ref,status,expires_at,"
                + "lifecycle_version,source_manifest_hash,disposition_revision,confirmation_operation_no)"
                + " VALUES(?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),2,?,1,?)",
                rowId, rowId, id, state, source.manifestHash(),
                confirmed ? StockDispositionContract.operationNo(source) : null);
        jdbc.update("INSERT INTO inv_reservation_line(reservation_id,line_no,owner_id,item_id,location_id,qty,"
                + "fulfillment_ref,source_sku_no) VALUES(?,1,?,?,?,1,?,?)", rowId, rowId, rowId, rowId, id, rowId);
        return source;
    }

    private void assertTerminal(String state, int onHand, int issued, int released, int reserved) {
        assertThat(jdbc.queryForObject("SELECT status FROM inv_reservation WHERE reservation_id=?", String.class, id))
                .isEqualTo(state);
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved FROM inv_stock_balance WHERE owner_id=?", id))
                .containsEntry("on_hand", onHand).containsEntry("reserved", reserved);
        assertThat(jdbc.queryForMap("SELECT issued_qty,released_qty FROM inv_reservation_line WHERE reservation_id=?", id))
                .containsEntry("issued_qty", issued).containsEntry("released_qty", released);
        assertThat(jdbc.queryForObject("SELECT disposition_revision FROM inv_reservation WHERE reservation_id=?",
                Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT source_manifest_hash FROM inv_reservation WHERE reservation_id=?",
                String.class, id)).isEqualTo(original.manifestHash());
    }

    private <T> T mapper(Class<T> type) {
        return context.getBean(type);
    }

    private void race(Runnable observer, Runnable writer) throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var observation = workers.submit(() -> runAs("observer", observer));
            try {
                assertThat(schedule.observerHasHeads.await(5, TimeUnit.SECONDS)).isTrue();
                var mutation = workers.submit(() -> runAs("writer", writer));
                observation.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                assertThat(schedule.observerConnection).isNotEqualTo(schedule.writerConnection);
            } finally {
                schedule.writerWantsHead.countDown();
                workers.shutdownNow();
            }
        }
    }

    private void runAs(String role, Runnable operation) {
        schedule.role.set(role);
        try {
            tx.executeWithoutResult(status -> {
                long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                if ("observer".equals(role)) schedule.observerConnection = connection;
                else schedule.writerConnection = connection;
                operation.run();
            });
        } finally {
            schedule.role.remove();
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class LockSchedule implements Interceptor {
        private final ThreadLocal<String> role = new ThreadLocal<>();
        private final ThreadLocal<List<String>> trace = new ThreadLocal<>();
        private final CountDownLatch observerHasHeads = new CountDownLatch(1);
        private final CountDownLatch writerWantsHead = new CountDownLatch(1);
        private volatile long observerConnection;
        private volatile long writerConnection;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            String sql = statement.getBoundSql(invocation.getArgs()[1]).getSql()
                    .replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (trace.get() != null) {
                trace.get().add(sql);
            }
            boolean headLock = sql.contains("FROM INV_RESERVATION ") && sql.contains("FOR UPDATE");
            if (headLock && "writer".equals(role.get())) writerWantsHead.countDown();
            Object result = invocation.proceed();
            if (headLock && "observer".equals(role.get()) && observerHasHeads.getCount() > 0) {
                observerHasHeads.countDown();
                if (!writerWantsHead.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Writer never reached the competing reservation HEAD lock");
                }
            }
            return result;
        }
    }
}
