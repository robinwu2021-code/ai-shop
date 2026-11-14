package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CreateDiningSettlementReq;
import ai.neargo.shop.food.dto.PayDiningSettlementReq;
import ai.neargo.shop.food.dto.DiningSettlementVersionReq;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ChargeIntentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.impl.DiningTransactionRecoveryServiceImpl;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real persisted FOOD scopes and charge intents; the same tests can use disposable native MariaDB. */
class DiningCheckoutServiceTest extends FoodWriteGuardFixture {
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private static final CartAccess BUYER = new CartAccess(null, null, "BUYER");
    private static final String DIGEST = "d".repeat(64);
    private record Context(Fixture db, DiningCheckoutService service, Supplier<DiningCheckoutService> restart,
            IndustryCheckoutPort core, OutboxPublisher publisher, SalesQuotaClaimService quotas,
            AtomicReference<IndustryCheckoutPort.View> actual, AtomicBoolean timeout, AtomicInteger sends,
            Function<Instant, DiningTransactionRecoveryServiceImpl> recovery) { }
    private void run(Consumer<Context> test) {
        runner().run(context -> {
            var f = prepare(context);
            String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                    + "created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                    + "version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            f.sql().execute("CREATE TABLE fnb_order_batch (" + common + "batch_no VARCHAR(32),store_no VARCHAR(32),check_no VARCHAR(32),"
                    + "request_key VARCHAR(64),request_hash VARCHAR(64),source VARCHAR(32),buyer_no VARCHAR(32),party_no VARCHAR(32),operator_no VARCHAR(32),"
                    + "status VARCHAR(24),frozen_request TEXT,accepted_at DATETIME(3),cart_no VARCHAR(32),cart_version BIGINT,quote_ref VARCHAR(32),"
                    + "quote_hash VARCHAR(64),business_date DATE,policy_no VARCHAR(32),fulfillment VARCHAR(32),pay_mode VARCHAR(16),"
                    + "scheduled_at DATETIME(3),actor_realm VARCHAR(32),payer_ref VARCHAR(32),quota_claim_refs TEXT,next_recovery_at DATETIME(3))");
            f.sql().execute("CREATE TABLE fnb_core_order_binding (" + common + "binding_no VARCHAR(32),batch_no VARCHAR(32),payment_root_no VARCHAR(32),"
                    + "store_order_no VARCHAR(32) UNIQUE,status VARCHAR(24))");
            f.sql().execute("CREATE TABLE fnb_settlement (" + common + "settlement_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),currency VARCHAR(3),"
                    + "status VARCHAR(24),scope_hash VARCHAR(64),quote_ref VARCHAR(64),due_minor BIGINT,core_cashier_ref VARCHAR(64),"
                    + "expires_at DATETIME(3),request_key VARCHAR(64),request_hash VARCHAR(64),request_snapshot TEXT,buyer_no VARCHAR(32),party_no VARCHAR(32),next_recovery_at DATETIME(3),"
                    + "UNIQUE(entity_no,store_no,request_key))");
            f.sql().execute("CREATE TABLE fnb_settlement_item (" + common + "scope_no VARCHAR(32) UNIQUE,settlement_no VARCHAR(32),check_no VARCHAR(32),"
                    + "payment_root_no VARCHAR(32),store_order_no VARCHAR(32),allocation_ref VARCHAR(64),due_minor BIGINT,core_version VARCHAR(64),"
                    + "scope_key VARCHAR(128),UNIQUE(settlement_no,scope_key))");
            f.sql().execute("CREATE TABLE fnb_charge_intent (" + common + "intent_no VARCHAR(32) UNIQUE,settlement_no VARCHAR(32),kind VARCHAR(24),"
                    + "request_key VARCHAR(64),core_reference VARCHAR(64),status VARCHAR(24),request_hash VARCHAR(64),cash_shift_no VARCHAR(32),"
                    + "request_snapshot TEXT,UNIQUE(settlement_no,request_key))");
            f.sql().execute("CREATE TABLE fnb_return (" + common + "batch_no VARCHAR(32),status VARCHAR(24),financial_status VARCHAR(24))");
            f.sql().update("INSERT INTO fnb_order_batch(entity_no,batch_no,store_no,buyer_no,status,cart_no,cart_version,source,fulfillment,pay_mode,"
                    + "quote_ref,quote_hash,business_date) VALUES('E','B','A','BUYER','BOUND','C',7,'H5','PICKUP','PREPAY','PQ',?,'2026-10-23')", DIGEST);
            f.sql().update("INSERT INTO fnb_core_order_binding(entity_no,binding_no,batch_no,payment_root_no,store_order_no,status) "
                    + "VALUES('E','BIND','B','ORDER','SUB','BOUND')");
            var sql = context.getBean(SqlSessionTemplate.class);
            for (var type : List.of(OrderBatchMapper.class, CoreOrderBindingMapper.class, SettlementMapper.class,
                    SettlementItemMapper.class, ChargeIntentMapper.class)) if (!sql.getConfiguration().hasMapper(type)) sql.getConfiguration().addMapper(type);
            var carts = mock(CheckCartService.class);
            // Public cart buyer is intentionally redacted; persisted batch plus successful owner authorization is the identity proof.
            when(carts.customerGetCart("C", BUYER)).thenReturn(new CartResultVO(new CartVO("C", "A", null,
                    "H5_SESSION", null, "CLOSED", "H5", "PICKUP", null, 8L, List.of())));
            var core = mock(IndustryCheckoutPort.class); var actual = new AtomicReference<>(result("WAIT_PAY", "NOT_STARTED", false));
            var timeout = new AtomicBoolean(); var sends = new AtomicInteger();
            when(core.get(any(), any())).thenAnswer(invocation -> { noRpcTransaction(); return actual.get(); });
            when(core.queryPayment(any(), any())).thenAnswer(invocation -> { noRpcTransaction(); return actual.get(); });
            when(core.finalizeCheckout(any(), any())).thenAnswer(invocation -> { noRpcTransaction(); return actual.get(); });
            when(core.pay(any())).thenAnswer(invocation -> {
                noRpcTransaction(); sends.incrementAndGet();
                assertThat(f.sql().queryForObject("SELECT status FROM fnb_charge_intent", String.class)).isEqualTo("SENT");
                if (timeout.get()) throw new IllegalStateException("owner response lost"); return actual.get();
            });
            var quotas = mock(SalesQuotaClaimService.class);
            when(quotas.consume(any(), any(), any())).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return List.of();
            });
            var publisher = mock(OutboxPublisher.class);
            when(publisher.publish(any(), any())).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return null;
            });
            ObjectMapper json = context.getBean(ObjectMapper.class);
            Supplier<DiningCheckoutService> restart = () -> new DiningCheckoutServiceImpl(carts,
                    sql.getMapper(OrderBatchMapper.class), sql.getMapper(CoreOrderBindingMapper.class), sql.getMapper(SettlementMapper.class),
                    sql.getMapper(SettlementItemMapper.class), sql.getMapper(ChargeIntentMapper.class), quotas, () -> core, publisher,
                    json, Clock.fixed(NOW, ZoneOffset.UTC), context.getBean(PlatformTransactionManager.class));
            var service = restart.get();
            // The real recovery scanner over the same rows; only the settlement queue holds candidates here.
            Function<Instant, DiningTransactionRecoveryServiceImpl> recovery = at -> new DiningTransactionRecoveryServiceImpl(
                    sql.getMapper(OrderBatchMapper.class), sql.getMapper(SettlementMapper.class), mock(ReturnMapper.class),
                    mock(DiningOrderService.class), service, mock(DiningReturnService.class), Clock.fixed(at, ZoneOffset.UTC),
                    context.getBean(PlatformTransactionManager.class));
            test.accept(new Context(f, service, restart, core, publisher, quotas, actual, timeout, sends, recovery));
        });
    }
    private static void noRpcTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static IndustryCheckoutPort.View result(String order, String payment, boolean ready) {
        return new IndustryCheckoutPort.View("ORDER", "SUB", "PQ", 7, "a".repeat(64), "b".repeat(64), DIGEST,
                order, "CNY", 210, NOW.plusSeconds(900).toEpochMilli(),
                List.of(new IndustryCheckoutPort.ChannelVO("WECHAT", true, null), new IndustryCheckoutPort.ChannelVO("ALIPAY", true, null)),
                new IndustryCheckoutPort.PaymentVO("NOT_STARTED".equals(payment) ? null : "OUT", "WECHAT", payment, Map.of(), null), ready);
    }
    private static DiningSettlementVO create(Context c, String key) {
        return c.service.customerCreate(BUYER, "B", new CreateDiningSettlementReq(DIGEST), key);
    }
    @Test void originalKeyReplaysSameFrozenRootAcrossRestartAndChangedRequestFails() {
        run(c -> {
            var original = create(c, "KEY");
            var replay = c.restart.get().customerCreate(BUYER, "B", new CreateDiningSettlementReq(DIGEST), "KEY");
            assertThat(replay.settlementNo()).isEqualTo(original.settlementNo());
            assertThat(replay.dueMinor()).isEqualTo(210); assertThat(original.checkNo()).isNull();
            assertThat(c.db.sql().queryForObject("SELECT COUNT(*) FROM fnb_settlement_item", Integer.class)).isOne();
            assertThatThrownBy(() -> c.service.customerCreate(BUYER, "B", new CreateDiningSettlementReq("e".repeat(64)), "KEY"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void concurrentDifferentKeysCannotDoubleClaimFullRootOrDoubleReportDue() {
        run(c -> {
            var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
            try {
                var first = executor.submit(() -> { start.await(); try { create(c, "FIRST"); return 1; } catch (BizException rejected) { return 0; } });
                var second = executor.submit(() -> { start.await(); try { create(c, "SECOND"); return 1; } catch (BizException rejected) { return 0; } });
                start.countDown(); assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(c.db.sql().queryForObject("SELECT COUNT(*) FROM fnb_settlement WHERE status IN ('FROZEN','COLLECTING','SETTLED')", Integer.class)).isOne();
                assertThat(c.db.sql().queryForObject("SELECT SUM(due_minor) FROM fnb_settlement WHERE status<>'CANCELLED'", Long.class)).isEqualTo(210);
            } catch (Exception failure) { throw new AssertionError(failure); } finally { executor.shutdownNow(); }
        });
    }
    @Test void lostPayResponseSurvivesRestartAndOriginalUnknownNeverCreatesAnotherLeg() {
        run(c -> {
            var source = create(c, "CREATE"); c.timeout.set(true);
            var request = new PayDiningSettlementReq(source.version(), DIGEST, "WECHAT");
            var pending = c.service.customerPay(BUYER, source.settlementNo(), request, "PAY");
            assertThat(pending.payment().status()).isEqualTo("UNKNOWN"); assertThat(pending.status()).isEqualTo("COLLECTING");
            c.timeout.set(false); c.actual.set(result("WAIT_PAY", "UNKNOWN", false));
            c.restart.get().customerPay(BUYER, source.settlementNo(), request, "PAY");
            assertThat(c.sends.get()).isOne();
            assertThatThrownBy(() -> c.service.customerPay(BUYER, source.settlementNo(),
                    new PayDiningSettlementReq(source.version(), DIGEST, "ALIPAY"), "OTHER"))
                    .isInstanceOf(BizException.class);
            assertThat(c.db.sql().queryForObject("SELECT COUNT(*) FROM fnb_charge_intent", Integer.class)).isOne();
            assertThatThrownBy(() -> c.service.customerCancel(BUYER, source.settlementNo(), new DiningSettlementVersionReq(1)))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void cashSuccessSettlesOnceButStockHeldCannotConsumeQuotaOrAdmitKitchen() {
        run(c -> {
            var source = create(c, "CREATE"); c.actual.set(result("PAID", "SUCCESS", false));
            var paid = c.service.customerFinalize(BUYER, source.settlementNo());
            assertThat(paid.status()).isEqualTo("SETTLED"); assertThat(paid.batchStatus()).isEqualTo("BOUND");
            verify(c.quotas, never()).consume(any(), any(), any()); verify(c.publisher, never()).publish(any(), any());
            c.actual.set(result("PAID", "SUCCESS", true));
            var accepted = c.service.customerFinalize(BUYER, source.settlementNo());
            assertThat(accepted.batchStatus()).isEqualTo("ACCEPTED"); assertThat(accepted.readyToAccept()).isTrue();
            c.restart.get().customerFinalize(BUYER, source.settlementNo());
            verify(c.quotas).consume("E", "A", "B"); verify(c.publisher).publish(any(), any());
            assertThat(c.db.sql().queryForObject("SELECT COUNT(*) FROM fnb_settlement WHERE status='SETTLED'", Integer.class)).isOne();
        });
    }
    @Test void outboxFailureRollsBackAcceptanceAndCanRetryWithoutAnotherPayment() {
        run(c -> {
            var source = create(c, "CREATE"); c.actual.set(result("PAID", "SUCCESS", true));
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                throw new IllegalStateException("outbox unavailable");
            }).when(c.publisher).publish(any(), any());
            assertThatThrownBy(() -> c.service.customerFinalize(BUYER, source.settlementNo())).isInstanceOf(IllegalStateException.class);
            assertThat(c.db.sql().queryForObject("SELECT status FROM fnb_order_batch", String.class)).isEqualTo("BOUND");
            assertThat(c.db.sql().queryForObject("SELECT accepted_at FROM fnb_order_batch", Object.class)).isNull();
            assertThat(c.db.sql().queryForObject("SELECT status FROM fnb_settlement", String.class)).isEqualTo("SETTLED");
        });
    }
    @Test void sameTotalWithDifferentEconomicSnapshotCannotStartOrConfirmPayment() {
        run(c -> {
            var source = create(c, "CREATE"); var original = c.actual.get();
            c.actual.set(new IndustryCheckoutPort.View(original.orderNo(), original.subOrderNo(), original.quoteNo(),
                    original.sourceVersion(), original.sourceHash(), "e".repeat(64), original.confirmationDigest(),
                    original.orderStatus(), original.currency(), original.amountMinor(), original.payDeadlineAt(), original.channels(), original.payment(), false));
            assertThatThrownBy(() -> c.service.customerPay(BUYER, source.settlementNo(),
                    new PayDiningSettlementReq(source.version(), DIGEST, "WECHAT"), "PAY")).isInstanceOf(BizException.class);
            assertThat(c.db.sql().queryForObject("SELECT COUNT(*) FROM fnb_charge_intent", Integer.class)).isZero();
            assertThat(c.sends.get()).isZero();
        });
    }
    @Test void pendingNativeReturnBlocksFirstAcceptanceAndStaffNeverReceivesPayerArguments() {
        run(c -> {
            var source = create(c, "CREATE"); c.actual.set(result("PAID", "SUCCESS", true));
            c.db.sql().update("INSERT INTO fnb_return(entity_no,batch_no,status,financial_status) VALUES('E','B','REQUESTED','PENDING')");
            assertThatThrownBy(() -> c.service.customerFinalize(BUYER, source.settlementNo())).isInstanceOf(BizException.class);
            verify(c.quotas, never()).consume(any(), any(), any());
            assertThat(c.service.staffGet("E", "A", "STAFF", source.settlementNo()).payment()).isNull();
        });
    }
    private static long version(Context c) {
        return c.db.sql().queryForObject("SELECT version FROM fnb_settlement", Long.class);
    }
    /** C2: the recovery lease is bookkeeping, not a business change, so the pay page's echoed version stays valid. */
    @Test void recoveryLeasesOverAnOpenPayPageNeverInvalidateTheEchoedVersionForPay() {
        run(c -> {
            var source = create(c, "CREATE");
            assertThat(c.recovery.apply(NOW).recoverDue(200).pending()).isOne();
            // The customer is still on the pay page when the lease expires and the next job run re-claims.
            assertThat(c.recovery.apply(NOW.plusSeconds(180)).recoverDue(200).claimed()).isOne();
            assertThat(version(c)).isEqualTo(source.version());
            var paying = c.service.customerPay(BUYER, source.settlementNo(),
                    new PayDiningSettlementReq(source.version(), DIGEST, "WECHAT"), "PAY");
            assertThat(paying.status()).isEqualTo("COLLECTING");
            assertThat(c.sends.get()).isOne();
        });
    }
    @Test void recoveryLeasesOverAnOpenPayPageNeverInvalidateTheEchoedVersionForCancel() {
        run(c -> {
            var source = create(c, "CREATE");
            c.recovery.apply(NOW).recoverDue(200);
            c.recovery.apply(NOW.plusSeconds(180)).recoverDue(200);
            var cancelled = c.service.customerCancel(BUYER, source.settlementNo(),
                    new DiningSettlementVersionReq(source.version()));
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
        });
    }
    /** A recovery that does change business state advances the version exactly once and fences stale writes. */
    @Test void recoveryThatSettlesAdvancesVersionOnceAndStalePayOrCancelStillConflicts() {
        run(c -> {
            var source = create(c, "CREATE");
            c.actual.set(result("PAID", "SUCCESS", false)); // paid on the owner side; FOOD never sent a charge
            assertThat(c.recovery.apply(NOW).recoverDue(200).pending()).isOne();
            assertThat(c.db.sql().queryForObject("SELECT status FROM fnb_settlement", String.class)).isEqualTo("SETTLED");
            assertThat(version(c)).isEqualTo(source.version() + 1);
            assertThatThrownBy(() -> c.service.customerPay(BUYER, source.settlementNo(),
                    new PayDiningSettlementReq(source.version(), DIGEST, "WECHAT"), "PAY")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> c.service.customerCancel(BUYER, source.settlementNo(),
                    new DiningSettlementVersionReq(source.version()))).isInstanceOf(BizException.class);
            assertThat(c.sends.get()).isZero();
            assertThat(c.db.sql().queryForObject("SELECT COUNT(*) FROM fnb_charge_intent", Integer.class)).isZero();
            assertThat(c.db.sql().queryForObject("SELECT status FROM fnb_settlement", String.class)).isEqualTo("SETTLED");
        });
    }
    @Test void finishedRecoveryRetiresTheLeaseWithoutAnotherVersionStep() {
        run(c -> {
            var source = create(c, "CREATE");
            c.actual.set(result("PAID", "SUCCESS", true));
            assertThat(c.recovery.apply(NOW).recoverDue(200).complete()).isOne();
            assertThat(version(c)).isEqualTo(source.version() + 1); // the SETTLED projection only
            assertThat(c.recovery.apply(NOW.plusSeconds(3600)).recoverDue(200).claimed()).isZero();
        });
    }
}
