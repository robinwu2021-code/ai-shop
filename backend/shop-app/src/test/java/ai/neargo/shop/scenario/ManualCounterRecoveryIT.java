package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.invbridge.InvMirrorEvent;
import ai.neargo.shop.invbridge.port.DualWriteStockPort;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationDispositionService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.impl.ReservationDispositionServiceImpl;
import ai.neargo.shop.inventory.service.impl.ReservationServiceImpl;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.product.service.impl.StockDispositionServiceImpl;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationLookup;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlacementService;
import ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService;
import ai.neargo.shop.trade.order.service.create.impl.CounterOrderPlacementServiceImpl;
import ai.neargo.shop.trade.order.service.create.impl.OrderCreationRecoveryServiceImpl;
import ai.neargo.shop.trade.service.CheckoutOrderWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

/** Real dual producer, platform authority, inventory owner and original intent arbitration. */
@TestPropertySource(
        properties = {
            "shop.inventory.enabled=true",
            "shop.inventory.flyway-enabled=false",
            "shop.inventory.datasource.url=${MANUAL_INVENTORY_MYSQL_URL}",
            "shop.inventory.datasource.username=root",
            "shop.inventory.datasource.password=${MANUAL_SERVICE_TEST_PASSWORD}",
            "shop.inventory.stock-authority=DUAL"
        })
class ManualCounterRecoveryIT extends ManualCounterPurchaseIT {
    @MockitoSpyBean StockPort selectedStock;
    @MockitoSpyBean OrderCreationRecoveryService legacyRecovery;
    @MockitoSpyBean CheckoutOrderWriter competingWriter;
    @MockitoSpyBean CheckoutMappers.OperationMapper competingOperations;
    @Autowired CounterOrderPlacementService counterRecovery;
    @Autowired InventoryAclService inventoryAcl;
    @Autowired LocationService locations;
    @Autowired StockCountService counts;
    @Autowired ReservationService reservations;
    @Autowired ReservationDispositionService inventoryOwner;

    @Autowired
    @Qualifier("invDataSource")
    DataSource invSource;

    @MockitoSpyBean StockDispositionService actualPlatformOwner;
    @MockitoSpyBean StockPortImpl actualPlatformCommands;
    @MockitoSpyBean OutboxEventBus actualOutbox;
    @MockitoSpyBean BalanceMapper cancellationBalances;
    @MockitoSpyBean ReservationLineMapper cancellationLines;
    @MockitoSpyBean ReservationMapper cancellationHeads;

    @BeforeEach
    void actualRecoveryOwners() throws Exception {
        origin(selectedStock, DualWriteStockPort.class);
        origin(legacyRecovery, OrderCreationRecoveryServiceImpl.class);
        origin(counterRecovery, CounterOrderPlacementServiceImpl.class);
        origin(inventoryOwner, ReservationDispositionServiceImpl.class);
        origin(reservations, ReservationServiceImpl.class);
        origin(actualPlatformOwner, StockDispositionServiceImpl.class);
        origin(actualPlatformCommands, StockPortImpl.class);
        try (var connection = invSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
    }

    @Test
    void heldCancellationAndExpiryRecordCompleteProgress() {
        for (int mode = 0; mode < 3; mode++) {
            int lifecycle = mode == 1 ? 2 : 1;
            boolean tracked = mode != 2;
            for (boolean expire : List.of(false, true)) {
                var original =
                        heldInventoryCase("compat-" + mode + '-' + expire, lifecycle, tracked);
                var inv = new JdbcTemplate(invSource);
                if (expire) {
                    inv.update(
                            "UPDATE inv_reservation SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)"
                                    + " WHERE owner_id=? AND external_ref=?",
                            original.owner(),
                            original.reference());
                    assertThat(reservations.expireOverdue(100)).isGreaterThanOrEqualTo(1);
                } else {
                    reservations.cancelByRef(original.owner(), original.reference());
                }
                assertHeldReleased(original, expire ? "EXPIRED" : "RELEASED");
                var terminal = inventoryOriginalSnapshot(original);
                reservations.cancelByRef(original.owner(), original.reference());
                reservations.expireOverdue(100);
                assertThat(inventoryOriginalSnapshot(original)).isEqualTo(terminal);
            }
        }
        // A HELD head cannot authorize cancellation of an already progressed line.
        for (boolean released : List.of(false, true)) {
            var original = heldInventoryCase("progress-" + released, 2, true);
            var inv = new JdbcTemplate(invSource);
            inv.update(
                    "UPDATE inv_reservation_line SET issued_qty=?,released_qty=? WHERE reservation_id=?",
                    released ? 0 : 1,
                    released ? 1 : 0,
                    original.reservation());
            var before = inventoryOriginalSnapshot(original);
            assertThatThrownBy(
                            () -> reservations.cancelByRef(original.owner(), original.reference()))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            failure ->
                                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(inventoryOriginalSnapshot(original)).isEqualTo(before);
            inv.update(
                    "UPDATE inv_reservation_line SET issued_qty=0,released_qty=0 WHERE reservation_id=?",
                    original.reservation());
            reservations.cancelByRef(original.owner(), original.reference());
            assertHeldReleased(original, "RELEASED");
        }
        System.out.println(
                "TASK66_HELD_COMPAT trackedV1/trackedV2/untracked cancel/expiry/replay/progress PASS");
    }

    @Test
    void heldCancellationWriteFailureRollsBackOwnerRows() {
        var realLines =
                mockingDetails(cancellationLines).getMockCreationSettings().getDefaultAnswer();
        var realHeads =
                mockingDetails(cancellationHeads).getMockCreationSettings().getDefaultAnswer();
        assertThat(realLines).isNotEqualTo(CALLS_REAL_METHODS);
        assertThat(realHeads).isNotEqualTo(CALLS_REAL_METHODS);
        for (String stage : List.of("BALANCE", "LINE", "HEAD")) {
            var original =
                    heldInventoryCase("rollback-" + stage, "BALANCE".equals(stage) ? 1 : 2, true);
            var inv = new JdbcTemplate(invSource);
            var before = inventoryOriginalSnapshot(original);
            var intercepted = new AtomicBoolean();
            if ("BALANCE".equals(stage)) {
                doAnswer(
                                call -> {
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isTrue();
                                    intercepted.set(true);
                                    return 0;
                                })
                        .when(cancellationBalances)
                        .unhold(
                                eq(original.owner()),
                                eq(original.item()),
                                eq(original.location()),
                                anyInt());
            } else if ("LINE".equals(stage)) {
                doAnswer(
                                call -> {
                                    InvReservationLine line = call.getArgument(0);
                                    if (!original.reservation().equals(line.getReservationId()))
                                        return realLines.answer(call);
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isTrue();
                                    assertThat(
                                                    inv.queryForObject(
                                                            "SELECT reserved FROM inv_stock_balance WHERE owner_id=?"
                                                                    + " AND item_id=? AND location_id=?",
                                                            Integer.class,
                                                            original.owner(),
                                                            original.item(),
                                                            original.location()))
                                            .isZero();
                                    intercepted.set(true);
                                    return 0;
                                })
                        .when(cancellationLines)
                        .updateById(any(InvReservationLine.class));
            } else {
                doAnswer(
                                call -> {
                                    InvReservation head = call.getArgument(0);
                                    if (!original.reservation().equals(head.getReservationId()))
                                        return realHeads.answer(call);
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isTrue();
                                    assertThat(
                                                    inv.queryForObject(
                                                            "SELECT reserved FROM inv_stock_balance WHERE owner_id=?"
                                                                    + " AND item_id=? AND location_id=?",
                                                            Integer.class,
                                                            original.owner(),
                                                            original.item(),
                                                            original.location()))
                                            .isZero();
                                    assertThat(
                                                    inv.queryForObject(
                                                            "SELECT released_qty FROM inv_reservation_line"
                                                                    + " WHERE reservation_id=?",
                                                            Integer.class,
                                                            original.reservation()))
                                            .isEqualTo(2);
                                    intercepted.set(true);
                                    return 0;
                                })
                        .when(cancellationHeads)
                        .updateById(any(InvReservation.class));
            }
            try {
                assertThatThrownBy(
                                () ->
                                        reservations.cancelByRef(
                                                original.owner(), original.reference()))
                        .isInstanceOfSatisfying(
                                BizException.class,
                                failure ->
                                        assertThat(failure.errorCode())
                                                .isEqualTo(ErrorCode.CONFLICT));
                assertThat(intercepted).isTrue();
                assertThat(inventoryOriginalSnapshot(original)).isEqualTo(before);
            } finally {
                reset(cancellationBalances, cancellationLines, cancellationHeads);
            }
            reservations.cancelByRef(original.owner(), original.reference());
            assertHeldReleased(original, "RELEASED");
        }
        System.out.println(
                "TASK66_HELD_ATOMIC balance/line/head affected0 rollback+realretry PASS");
    }

    private HeldInventoryCase heldInventoryCase(String suffix, int lifecycle, boolean tracked) {
        var inv = new JdbcTemplate(invSource);
        String owner = inventoryAcl.ownerIdOf(entity);
        String item = inventoryAcl.itemIdOf(entity, sku);
        String location = locations.defaultLocation(owner);
        counts.adjustOne(owner, location, item, 10, "OTHER", "SYSTEM");
        var baseline =
                inv.queryForMap(
                        "SELECT on_hand,reserved FROM inv_stock_balance WHERE owner_id=?"
                                + " AND item_id=? AND location_id=?",
                        owner,
                        item,
                        location);
        assertThat(baseline).containsEntry("reserved", 0);
        String reference = "held-" + suffix + '-' + sku;
        String fulfillment = "held-line-" + suffix + '-' + sku;
        var line =
                tracked
                        ? new ReservationService.Line(item, location, 2, fulfillment, sku, store)
                        : new ReservationService.Line(item, location, 2);
        String reservation = reservations.reserve(owner, reference, List.of(line), 1800, lifecycle);
        return new HeldInventoryCase(
                owner,
                item,
                location,
                reference,
                fulfillment,
                reservation,
                lifecycle,
                tracked,
                baseline);
    }

    private void assertHeldReleased(HeldInventoryCase original, String state) {
        var inv = new JdbcTemplate(invSource);
        var head =
                inv.queryForMap(
                        "SELECT status,disposition_revision,source_manifest_hash FROM inv_reservation"
                                + " WHERE reservation_id=?",
                        original.reservation());
        assertThat(head)
                .containsEntry("status", state)
                .containsEntry("disposition_revision", original.tracked() ? 2L : null);
        assertThat(
                        inv.queryForList(
                                "SELECT qty,issued_qty,released_qty FROM inv_reservation_line"
                                        + " WHERE reservation_id=?",
                                original.reservation()))
                .hasSize(1)
                .allSatisfy(
                        line ->
                                assertThat(line)
                                        .containsEntry("qty", 2)
                                        .containsEntry("issued_qty", 0)
                                        .containsEntry("released_qty", 2));
        assertThat(
                        inv.queryForMap(
                                "SELECT on_hand,reserved FROM inv_stock_balance WHERE owner_id=?"
                                        + " AND item_id=? AND location_id=?",
                                original.owner(),
                                original.item(),
                                original.location()))
                .isEqualTo(original.baseline());
        if (original.tracked()) {
            var needs =
                    List.of(new StockDispositionPort.Need(sku, store, original.fulfillment(), 2));
            var frozen =
                    new Original(
                            "MAIN",
                            entity,
                            original.reference(),
                            "INVENTORY",
                            original.lifecycle(),
                            StockDispositionContract.manifestHash(needs),
                            needs);
            var proof =
                    originalStock.releaseOriginal(
                            new ReservationLookup(
                                    frozen,
                                    StockDispositionContract.reservationOperationNo(frozen)));
            assertThat(proof.outcome()).isEqualTo("RELEASED");
            assertThat(proof.lines()).hasSize(1);
        } else {
            assertThat(head).containsEntry("source_manifest_hash", null);
        }
    }

    private Map<String, Object> inventoryOriginalSnapshot(HeldInventoryCase original) {
        var inv = new JdbcTemplate(invSource);
        return Map.of(
                "head",
                inv.queryForMap(
                        "SELECT * FROM inv_reservation WHERE reservation_id=?",
                        original.reservation()),
                "lines",
                inv.queryForList(
                        "SELECT * FROM inv_reservation_line WHERE reservation_id=? ORDER BY line_no",
                        original.reservation()),
                "balance",
                inv.queryForMap(
                        "SELECT * FROM inv_stock_balance WHERE owner_id=? AND item_id=?"
                                + " AND location_id=?",
                        original.owner(),
                        original.item(),
                        original.location()));
    }

    private record HeldInventoryCase(
            String owner,
            String item,
            String location,
            String reference,
            String fulfillment,
            String reservation,
            int lifecycle,
            boolean tracked,
            Map<String, Object> baseline) {}

    private void instrumentOriginalReserveBoundary() throws Exception {
        var actual = AopTestUtils.getUltimateTargetObject(originalStock);
        Object writes = ReflectionTestUtils.getField(actual, "ownerWrites");
        var provider = (ObjectProvider<?>) ReflectionTestUtils.getField(actual, "dualCommands");
        Object dual = null;
        try {
            dual = provider == null ? null : provider.getIfAvailable();
        } catch (RuntimeException failure) {
            diagnosticFailure("DUAL_PROVIDER", failure);
        }
        System.out.println(
                "TASK66_DUAL_DIAGNOSTIC ownerTransaction="
                        + (writes != null)
                        + " provider="
                        + (dual == null ? "ABSENT" : AopUtils.getTargetClass(dual).getName()));
        origin(actualOutbox, OutboxEventBus.class);
        doAnswer(
                        call -> {
                            try {
                                var result =
                                        (StockDispositionPort.ReservationView)
                                                call.callRealMethod();
                                System.out.println(
                                        "TASK66_DUAL_DIAGNOSTIC platformReserve="
                                                + result.outcome()
                                                + " reason="
                                                + result.reasonCode()
                                                + " ownerLines="
                                                + result.lines().size());
                                return result;
                            } catch (RuntimeException | Error failure) {
                                diagnosticFailure("PLATFORM_RESERVE", failure);
                                throw failure;
                            }
                        })
                .when(actualPlatformOwner)
                .reserveOriginal(any());
        doAnswer(
                        call -> {
                            try {
                                var result = (List<?>) call.callRealMethod();
                                System.out.println(
                                        "TASK66_DUAL_DIAGNOSTIC platformCommand failedCount="
                                                + result.size());
                                return result;
                            } catch (RuntimeException | Error failure) {
                                diagnosticFailure("PLATFORM_LOCK", failure);
                                throw failure;
                            }
                        })
                .when(actualPlatformCommands)
                .lock(anyString(), anyList());
        doAnswer(
                        call -> {
                            try {
                                var result = (List<?>) call.callRealMethod();
                                System.out.println(
                                        "TASK66_DUAL_DIAGNOSTIC dualProducer failedCount="
                                                + result.size());
                                return result;
                            } catch (RuntimeException | Error failure) {
                                diagnosticFailure("DUAL_LOCK_PRODUCER", failure);
                                throw failure;
                            }
                        })
                .when(selectedStock)
                .lock(anyString(), anyList());
        doAnswer(
                        call -> {
                            try {
                                Object result = call.callRealMethod();
                                System.out.println("TASK66_DUAL_DIAGNOSTIC outboxPublish=RETURNED");
                                return result;
                            } catch (RuntimeException | Error failure) {
                                diagnosticFailure("OUTBOX_PUBLISH", failure);
                                throw failure;
                            }
                        })
                .when(actualOutbox)
                .publish(any(DomainEvent.class));
    }

    private static void diagnosticFailure(String stage, Throwable failure) {
        for (int depth = 0; failure != null && depth < 4; depth++, failure = failure.getCause()) {
            System.out.println(
                    "TASK66_OWNER_DIAGNOSTIC stage="
                            + stage
                            + " causeDepth="
                            + depth
                            + " class="
                            + failure.getClass().getName()
                            + " code="
                            + (failure instanceof BizException business
                                    ? business.errorCode()
                                    : "NONE"));
            int emitted = 0;
            for (var frame : failure.getStackTrace()) {
                if (frame.getClassName().startsWith("ai.neargo.shop.") && emitted++ < 6) {
                    System.out.println(
                            "TASK66_OWNER_DIAGNOSTIC frame="
                                    + frame.getClassName()
                                    + '.'
                                    + frame.getMethodName()
                                    + ':'
                                    + frame.getLineNumber());
                }
            }
        }
    }

    @AfterEach
    void deferOnlyThisFixturePendingIntents() {
        // Other cases must not claim this case's deliberately unresolved original from the global
        // scanner.
        jdbc.update(
                "UPDATE ord_create_intent SET next_attempt_at=? WHERE entity_no=? AND store_no=?"
                        + " AND creation_kind='COUNTER' AND status IN ('PREPARED','RECOVERY_REQUIRED','COMPENSATING')",
                Long.MAX_VALUE,
                entity,
                store);
    }

    @Test
    void lostReserveReplyObservesOriginalRows() throws Exception {
        instrumentOriginalReserveBoundary();
        var quote =
                prepare(
                        counterRequest(
                                "lost-reserve",
                                new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        var firstObserve = new AtomicBoolean(true);
        var actualReserveReturned = new AtomicBoolean(false);
        doAnswer(
                        call -> {
                            var request = (StockDispositionPort.Reservation) call.getArgument(0);
                            unlocked(
                                    quote.quote().operation().operationNo(),
                                    request.original().orderNo());
                            var actual =
                                    (StockDispositionPort.ReservationView) call.callRealMethod();
                            System.out.println(
                                    "TASK66_RESERVE_DIAGNOSTIC outcome="
                                            + actual.outcome()
                                            + " reason="
                                            + actual.reasonCode()
                                            + " ownerLines="
                                            + actual.lines().size());
                            assertThat(actual.outcome()).isEqualTo("RESERVED");
                            actualReserveReturned.set(true);
                            throw new IllegalStateException("lost committed reserve reply");
                        })
                .when(originalStock)
                .reserveOriginal(any());
        doAnswer(
                        call -> {
                            if (actualReserveReturned.get() && firstObserve.getAndSet(false)) {
                                throw new IllegalStateException(
                                        "first external observation unavailable");
                            }
                            return call.callRealMethod();
                        })
                .when(originalStock)
                .observeReservation(any());
        var pending = place(accepted);
        assertThat(pending.phase()).isEqualTo("PLACING");
        var original =
                jdbc.queryForMap(
                        "SELECT pay_no,sub_order_no,reservation_state,stock_authority"
                                + " FROM ord_create_intent WHERE operation_no=?",
                        accepted.operation().operationNo());
        assertThat(original)
                .containsEntry("reservation_state", "UNKNOWN")
                .containsEntry("stock_authority", "DUAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                Long.class,
                                original.get("pay_no")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?",
                                Long.class,
                                original.get("pay_no")))
                .isEqualTo(1);
        var created = place(accepted);
        assertThat(created.phase()).isEqualTo("CREATED");
        assertThat(created.orderNo()).isEqualTo(original.get("pay_no"));
        assertThat(created.subOrderNo()).isEqualTo(original.get("sub_order_no"));
        assertThat(place(accepted).orderNo()).isEqualTo(created.orderNo());
        verify(originalStock, times(1)).reserveOriginal(any());
        verify(originalStock, never()).releaseOriginal(any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_type='INVENTORY'"
                                        + " AND aggregate_id=? AND event_type=?",
                                Long.class,
                                created.orderNo(),
                                InvMirrorEvent.RESERVE))
                .isEqualTo(1);
    }

    @Test
    void staleOriginalReleaseLostReplyRemainsCompensatingAndNeverReservesAgain() {
        var quote =
                prepare(
                        counterRequest(
                                "lost-release",
                                new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        doAnswer(
                        call -> {
                            var request = (StockDispositionPort.Reservation) call.getArgument(0);
                            unlocked(
                                    accepted.operation().operationNo(),
                                    request.original().orderNo());
                            var result = call.callRealMethod();
                            jdbc.update("UPDATE prd_sku SET price=price+1 WHERE sku_no=?", sku);
                            return result;
                        })
                .when(originalStock)
                .reserveOriginal(any());
        var lost = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            var request = (ReservationLookup) call.getArgument(0);
                            unlocked(
                                    accepted.operation().operationNo(),
                                    request.original().orderNo());
                            var actual = (StockDispositionPort.ReleaseView) call.callRealMethod();
                            assertThat(actual.outcome()).isEqualTo("RELEASED");
                            if (lost.getAndSet(false)) {
                                throw new IllegalStateException("lost committed release reply");
                            }
                            return actual;
                        })
                .when(originalStock)
                .releaseOriginal(any());
        assertThat(place(accepted).phase()).isEqualTo("REQUOTE_REQUIRED");
        var intent =
                jdbc.queryForMap(
                        "SELECT pay_no,status,reservation_state,reservation_observation_json"
                                + " FROM ord_create_intent WHERE operation_no=?",
                        accepted.operation().operationNo());
        assertThat(intent)
                .containsEntry("status", "COMPENSATING")
                .containsEntry("reservation_state", "RESERVED");
        assertThat(intent.get("reservation_observation_json").toString())
                .contains("UNKNOWN")
                .contains("schema");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                intent.get("pay_no")))
                .containsExactly("RELEASED");
        assertThat(place(accepted).phase()).isEqualTo("REQUOTE_REQUIRED");
        jdbc.update(
                "UPDATE ord_create_intent SET next_attempt_at=0 WHERE pay_no=?",
                intent.get("pay_no"));
        assertThat(counterRecovery.recoverPending(100)).isGreaterThanOrEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_create_intent WHERE pay_no=?",
                                String.class,
                                intent.get("pay_no")))
                .isEqualTo("RELEASED");
        verify(originalStock, times(1)).reserveOriginal(any());
        verify(originalStock, times(2)).releaseOriginal(any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                Long.class,
                                intent.get("pay_no")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
    }

    @Test
    void finalizeAndCompensateHaveOneWinner() {
        var quote =
                prepare(
                        counterRequest(
                                "committed-parent",
                                new CounterCustomerRef("USER", member, null, null)));
        var created = place(accept(quote));
        doThrow(new AssertionError("a committed parent cannot be compensated"))
                .when(originalStock)
                .releaseOriginal(any());
        jdbc.update(
                "UPDATE ord_create_intent SET status='PREPARED',next_attempt_at=0 WHERE pay_no=?",
                created.orderNo());
        counterRecovery.recoverPending(100);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_create_intent WHERE pay_no=?",
                                String.class,
                                created.orderNo()))
                .isEqualTo("COMMITTED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                created.orderNo()))
                .containsExactly("LOCKED");
        verify(originalStock, never()).releaseOriginal(any());
    }

    @Test
    void concurrentFinalizeBlocksExpiredCompensation() throws Exception {
        var quote =
                prepare(
                        counterRequest(
                                "competing-finalize",
                                new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        var rowsPersisted = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var compensationEnteringRoot = new CountDownLatch(1);
        var finalizerConnection = new AtomicLong();
        var compensationConnection = new AtomicLong();
        var realOperationLocks =
                mockingDetails(competingOperations).getMockCreationSettings().getDefaultAnswer();
        assertThat(realOperationLocks).isNotEqualTo(CALLS_REAL_METHODS);
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            jdbc.update(
                                    "UPDATE ord_create_intent SET next_attempt_at=0 WHERE pay_no=?",
                                    (String) call.getArgument(0));
                            return null;
                        })
                .when(legacyRecovery)
                .recordCounterReservation(anyString(), anyLong(), any());
        doAnswer(
                        call -> {
                            var actual = call.callRealMethod();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            finalizerConnection.set(
                                    jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            rowsPersisted.countDown();
                            assertThat(allowCommit.await(10, TimeUnit.SECONDS)).isTrue();
                            return actual;
                        })
                .when(competingWriter)
                .createCounterConfirmedFor(any(), any(), any());
        doAnswer(
                        call -> {
                            if ("task66-compensator".equals(Thread.currentThread().getName())
                                    && accepted.operation()
                                            .operationNo()
                                            .equals(call.getArgument(1))) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isTrue();
                                compensationConnection.set(
                                        jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                                compensationEnteringRoot.countDown();
                            }
                            var realRow = (OrdCheckoutOperation) realOperationLocks.answer(call);
                            assertThat(realRow).isNotNull();
                            assertThat(realRow.getOperationNo())
                                    .isEqualTo(call.getArgument(1, String.class));
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return realRow;
                        })
                .when(competingOperations)
                .lock(anyString(), anyString());
        doThrow(
                        new AssertionError(
                                "committing original cannot be released by expired compensation"))
                .when(originalStock)
                .releaseOriginal(any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var finalizing =
                    workers.submit(
                            () -> {
                                Thread.currentThread().setName("task66-finalizer");
                                return place(accepted);
                            });
            boolean persisted = rowsPersisted.await(10, TimeUnit.SECONDS);
            if (!persisted) {
                System.out.println("TASK66_FINALIZER_DIAGNOSTIC completed=" + finalizing.isDone());
                if (finalizing.isDone()) {
                    try {
                        System.out.println(
                                "TASK66_FINALIZER_DIAGNOSTIC phase=" + finalizing.get().phase());
                    } catch (ExecutionException failure) {
                        diagnosticFailure("FINALIZER_FUTURE", failure.getCause());
                    }
                }
                System.out.println(
                        "TASK66_FINALIZER_DIAGNOSTIC operationState="
                                + jdbc.queryForList(
                                        "SELECT phase FROM ord_checkout_operation WHERE operation_no=?",
                                        accepted.operation().operationNo())
                                + " intentState="
                                + jdbc.queryForList(
                                        "SELECT status,reservation_state FROM ord_create_intent WHERE operation_no=?",
                                        accepted.operation().operationNo()));
            }
            assertThat(persisted).isTrue();
            testClock.at = quote.quote().expiresAt().plusSeconds(1);
            var compensating =
                    workers.submit(
                            () -> {
                                Thread.currentThread().setName("task66-compensator");
                                return counterRecovery.recoverPending(100);
                            });
            try {
                assertThat(compensationEnteringRoot.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(finalizerConnection.get()).isPositive();
                assertThat(compensationConnection.get())
                        .isPositive()
                        .isNotEqualTo(finalizerConnection.get());
                assertThatThrownBy(() -> compensating.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                allowCommit.countDown();
            }
            var created = finalizing.get(15, TimeUnit.SECONDS);
            assertThat(created.phase()).isEqualTo("CREATED");
            compensating.get(15, TimeUnit.SECONDS);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_create_intent WHERE pay_no=?",
                                    String.class,
                                    created.orderNo()))
                    .isEqualTo("COMMITTED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                    Long.class,
                                    created.orderNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                    String.class,
                                    created.orderNo()))
                    .containsExactly("LOCKED");
            verify(originalStock, times(1)).reserveOriginal(any());
            verify(originalStock, never()).releaseOriginal(any());
            System.out.println(
                    "TASK66_FINALIZE_COMPENSATE differentConnections=true committedParentWins=true");
        } finally {
            allowCommit.countDown();
        }
    }

    @Test
    void createdRetryDoesNotLockParentAgainstReceiptProgress() throws Exception {
        var quote =
                prepare(
                        counterRequest(
                                "created-retry",
                                new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        var created = place(accepted);
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        created.orderNo());
        var receipt =
                new ConfirmManualReceipt(
                        accepted.operation(),
                        "fast-receipt-" + sku,
                        revision,
                        ManualMethod.CASH,
                        quote.quote().dueMinor(),
                        "CNY",
                        testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                        null);
        var entered = new CountDownLatch(1);
        try (var parent = dataSource.getConnection();
                var workers = Executors.newFixedThreadPool(2)) {
            parent.setAutoCommit(false);
            try (var lock =
                    parent.prepareStatement(
                            "SELECT id FROM ord_order WHERE order_no=? FOR UPDATE")) {
                lock.setString(1, created.orderNo());
                lock.executeQuery().close();
            }
            var progressing =
                    workers.submit(
                            () -> {
                                entered.countDown();
                                return asMerchant(() -> manual.confirm(scope, receipt));
                            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var retry =
                    workers.submit(
                            () ->
                                    asMerchant(
                                            () ->
                                                    manual.placeCounter(
                                                            scope,
                                                            new PlaceCounterReq(
                                                                    accepted.operation(),
                                                                    accepted.confirmationNo(),
                                                                    "place-"
                                                                            + accepted.operation()
                                                                                    .operationNo()))));
            try {
                assertThat(retry.get(3, TimeUnit.SECONDS).orderNo()).isEqualTo(created.orderNo());
            } finally {
                parent.commit();
            }
            assertThat(progressing.get(20, TimeUnit.SECONDS).admissionState())
                    .isEqualTo("ADMITTED");
        }
    }

    @Test
    void legacyScannerCannotReleaseCounterIntent() {
        var quote =
                prepare(
                        counterRequest(
                                "legacy-scan", new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new IllegalStateException("lost reserve");
                        })
                .when(originalStock)
                .reserveOriginal(any());
        doThrow(new IllegalStateException("observation unavailable"))
                .when(originalStock)
                .observeReservation(any());
        assertThat(place(accepted).phase()).isEqualTo("PLACING");
        var before =
                jdbc.queryForMap(
                        "SELECT pay_no,status,reservation_state,plan_hash FROM ord_create_intent"
                                + " WHERE operation_no=?",
                        accepted.operation().operationNo());
        legacyRecovery.recoverAbandoned(System.currentTimeMillis() + 300_000L);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT pay_no,status,reservation_state,plan_hash FROM ord_create_intent"
                                        + " WHERE operation_no=?",
                                accepted.operation().operationNo()))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                before.get("pay_no")))
                .containsExactly("LOCKED");
        verify(selectedStock, never()).release(anyString(), anyList());
        verify(originalStock, never()).releaseOriginal(any());
    }

    @Test
    void unknownReserveNeverBecomesCreated() {
        var quote =
                prepare(
                        counterRequest(
                                "unknown-reserve",
                                new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        doThrow(new IllegalStateException("dispatch outcome unavailable before owner result"))
                .when(originalStock)
                .reserveOriginal(any());
        assertThat(place(accepted).phase()).isEqualTo("PLACING");
        var before =
                jdbc.queryForMap(
                        "SELECT pay_no,sub_order_no,plan_hash FROM ord_create_intent WHERE operation_no=?",
                        accepted.operation().operationNo());
        for (int attempt = 0; attempt < 2; attempt++) {
            jdbc.update(
                    "UPDATE ord_create_intent SET next_attempt_at=0 WHERE pay_no=?",
                    before.get("pay_no"));
            counterRecovery.recoverPending(100);
        }
        assertThat(
                        jdbc.queryForMap(
                                "SELECT pay_no,sub_order_no,plan_hash FROM ord_create_intent WHERE operation_no=?",
                                accepted.operation().operationNo()))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                Long.class,
                                before.get("pay_no")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?",
                                Long.class,
                                before.get("pay_no")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reservation_state FROM ord_create_intent WHERE pay_no=?",
                                String.class,
                                before.get("pay_no")))
                .isEqualTo("UNKNOWN");
        verify(originalStock, never()).releaseOriginal(any());
    }

    @Test
    void claimDoesNotChangePendingOriginal() {
        var guest = registerGuest();
        var quote =
                prepare(
                        counterRequest(
                                "pending-claim",
                                new CounterCustomerRef(
                                        "GUEST", null, guest.guestNo(), guest.claimToken())));
        var accepted = accept(quote);
        var actualReserveReturned = new AtomicBoolean(false);
        doAnswer(
                        call -> {
                            var actual =
                                    (StockDispositionPort.ReservationView) call.callRealMethod();
                            assertThat(actual.outcome()).isEqualTo("RESERVED");
                            actualReserveReturned.set(true);
                            throw new IllegalStateException("lost committed reserve");
                        })
                .when(originalStock)
                .reserveOriginal(any());
        var lost = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            if (actualReserveReturned.get() && lost.getAndSet(false)) {
                                throw new IllegalStateException("lost external observation");
                            }
                            return call.callRealMethod();
                        })
                .when(originalStock)
                .observeReservation(any());
        assertThat(place(accepted).phase()).isEqualTo("PLACING");
        var before =
                jdbc.queryForMap(
                        "SELECT pay_no,sub_order_no,plan_hash,counter_plan_json FROM ord_create_intent"
                                + " WHERE operation_no=?",
                        accepted.operation().operationNo());
        var ownerScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        asMerchant(
                () ->
                        guests.claim(
                                ownerScope,
                                guest.guestNo(),
                                new ClaimCommand(
                                        reference, guest.claimToken(), "pending-claim-" + sku)));
        var created = place(accepted);
        assertThat(created.orderNo()).isEqualTo(before.get("pay_no"));
        assertThat(created.subOrderNo()).isEqualTo(before.get("sub_order_no"));
        assertThat(
                        jdbc.queryForMap(
                                "SELECT pay_no,sub_order_no,plan_hash,counter_plan_json FROM ord_create_intent"
                                        + " WHERE operation_no=?",
                                accepted.operation().operationNo()))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT customer_kind,user_no,guest_no,identity_revision FROM ord_order"
                                        + " WHERE order_no=?",
                                created.orderNo()))
                .containsEntry("customer_kind", "GUEST")
                .containsEntry("user_no", null)
                .containsEntry("guest_no", guest.guestNo())
                .containsEntry("identity_revision", guest.revision());
        assertThat(asBuyer(() -> writer.detail(created.orderNo(), null)).orderNo())
                .isEqualTo(created.orderNo());
    }

    @Test
    void frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges() {
        assertThat(AopUtils.getTargetClass(inventoryOwner))
                .isEqualTo(ReservationDispositionServiceImpl.class);
        var type = AopUtils.getTargetClass(inventoryOwner);
        assertThat(type.getProtectionDomain().getCodeSource().getLocation().toString())
                .contains("/beauty-data/ai-shop-codex/")
                .contains("/target/classes/");
        var inv = new JdbcTemplate(invSource);
        String owner = inventoryAcl.ownerIdOf(entity);
        String item = inventoryAcl.itemIdOf(entity, sku);
        String location = locations.defaultLocation(owner);
        counts.adjustOne(owner, location, item, 10, "OTHER", "SYSTEM");
        String no = "inventory-counter-original-" + sku;
        String fulfillment = "inventory-child-" + sku;
        reservations.reserve(
                owner,
                no,
                List.of(new ReservationService.Line(item, location, 1, fulfillment, sku, store)),
                1800,
                1);
        var needs = List.of(new StockDispositionPort.Need(sku, store, fulfillment, 1));
        var original =
                new Original(
                        "MAIN",
                        entity,
                        no,
                        "INVENTORY",
                        1,
                        StockDispositionContract.manifestHash(needs),
                        needs);
        var lookup =
                new ReservationLookup(
                        original, StockDispositionContract.reservationOperationNo(original));
        assertThat(originalStock.observeReservation(lookup).outcome()).isEqualTo("RESERVED");
        inv.update(
                "UPDATE inv_item_ref SET ref_system='T66_RETIRED' WHERE owner_id=?"
                        + " AND ref_system='AISHOP' AND ref=?",
                owner,
                sku);
        try {
            assertThat(inventoryAcl.itemIdOfSku(sku)).isNull();
            var release = originalStock.releaseOriginal(lookup);
            var headState =
                    inv.queryForObject(
                            "SELECT status FROM inv_reservation WHERE owner_id=? AND external_ref=?",
                            String.class,
                            owner,
                            no);
            var lineState =
                    inv.queryForList(
                            "SELECT l.qty,l.issued_qty,l.released_qty FROM inv_reservation_line l"
                                    + " JOIN inv_reservation h ON h.reservation_id=l.reservation_id"
                                    + " WHERE h.owner_id=? AND h.external_ref=? ORDER BY l.line_no",
                            owner,
                            no);
            var balanceState =
                    inv.queryForMap(
                            "SELECT on_hand,reserved FROM inv_stock_balance WHERE owner_id=? AND item_id=?"
                                    + " AND location_id=?",
                            owner,
                            item,
                            location);
            System.out.println(
                    "TASK66_RELEASE_DIAGNOSTIC outcome="
                            + release.outcome()
                            + " reason="
                            + release.reasonCode()
                            + " head="
                            + headState
                            + " lineQuantities="
                            + lineState
                            + " balanceQuantities="
                            + balanceState);
            assertThat(release.outcome()).isEqualTo("RELEASED");
            assertThat(release.original().authority()).isEqualTo("INVENTORY");
            assertThat(
                            inv.queryForObject(
                                    "SELECT status FROM inv_reservation WHERE owner_id=? AND external_ref=?",
                                    String.class,
                                    owner,
                                    no))
                    .isEqualTo("RELEASED");
            assertThat(
                            inv.queryForObject(
                                    "SELECT reserved FROM inv_stock_balance WHERE owner_id=? AND item_id=?"
                                            + " AND location_id=?",
                                    Integer.class,
                                    owner,
                                    item,
                                    location))
                    .isZero();
        } finally {
            inv.update(
                    "UPDATE inv_item_ref SET ref_system='AISHOP' WHERE owner_id=?"
                            + " AND ref_system='T66_RETIRED' AND ref=?",
                    owner,
                    sku);
        }
        assertThat(originalStock.releaseOriginal(lookup).outcome()).isEqualTo("RELEASED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?",
                                Long.class,
                                no))
                .isZero();
    }

    private void unlocked(String operation, String order) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String sql :
                        List.of(
                                "SELECT id FROM ord_checkout_operation WHERE operation_no=? FOR UPDATE NOWAIT",
                                "SELECT id FROM ord_create_intent WHERE pay_no=? FOR UPDATE NOWAIT")) {
                    try (var probe = connection.prepareStatement(sql)) {
                        probe.setString(1, sql.contains("operation_no") ? operation : order);
                        probe.executeQuery().close();
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException locked) {
            throw new AssertionError("Core roots held at original stock IO", locked);
        }
    }
}
