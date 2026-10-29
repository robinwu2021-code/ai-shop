package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.user.AppointmentSlotPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@TestPropertySource(
        properties =
                "shop.core.entitlement.migration-watermark-key=checkout-migration-test-watermark-key")
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutOwnerBoundaryFlowTest extends CheckoutFlowFixture {
    @Autowired private LegacyCouponSourceFenceService fences;
    @Autowired private StoreSalesCatalogPort catalog;
    @Autowired private StoreDirectoryPort directory;

    @Autowired
    @Qualifier("sqlSessionFactory")
    private SqlSessionFactory sessions;

    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager manager;

    @Test
    void catalogLockRequiresCurrentBuyerAndActualPlatformTransaction() {
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                catalog.lockForCheckout(
                                                        buyer, entity, store, testClock.instant())))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE));
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                new TransactionTemplate(manager)
                                                        .execute(
                                                                status ->
                                                                        catalog.lockForCheckout(
                                                                                "foreign",
                                                                                entity,
                                                                                store,
                                                                                testClock
                                                                                        .instant()))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                new TransactionTemplate(manager)
                                                        .execute(
                                                                status ->
                                                                        catalog.read(
                                                                                entity,
                                                                                store,
                                                                                testClock
                                                                                        .instant()))))
                .isInstanceOf(IllegalStateException.class);
        var locked =
                asBuyer(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status ->
                                                        catalog.lockForCheckout(
                                                                buyer,
                                                                entity,
                                                                store,
                                                                testClock.instant())));
        assertThat(locked).isNotNull();
        assertThatThrownBy(() -> directory.checkoutCardInCurrentTransaction("MAIN", entity, store))
                .isInstanceOf(BizException.class);
    }

    @Test
    void unsupportedDirectoryCapabilityNeverFallsBackToRemotePresentationRead() {
        StoreDirectoryPort remote =
                (StoreDirectoryPort)
                        Proxy.newProxyInstance(
                                StoreDirectoryPort.class.getClassLoader(),
                                new Class<?>[] {StoreDirectoryPort.class},
                                (proxy, method, args) -> {
                                    if (method.isDefault()) {
                                        return InvocationHandler.invokeDefault(proxy, method, args);
                                    }
                                    throw new AssertionError(
                                            "remote presentation method must not be called");
                                });
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status ->
                                                        remote.checkoutCardInCurrentTransaction(
                                                                "MAIN", entity, store)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void migrationWinsBeforeFinalLockAndRejectsBeforeIntent() {
        String coupon = promoCoupon("ALL", 0, 1);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var c = asBuyer(() -> confirm(q));
        long intents = count("ord_create_intent");
        var drained = drain();
        assertThat(drained.state()).isEqualTo("DRAINING");
        assertThatThrownBy(() -> asBuyer(() -> checkout.place(place(q, c))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION));
        assertThat(count("ord_create_intent")).isEqualTo(intents);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM pmt_user_coupon WHERE user_coupon_no=?",
                                String.class,
                                coupon))
                .isEqualTo("UNUSED");
    }

    @Test
    void actualCheckoutRootLockMakesMigrationWaitUntilCouponUseCommits() throws Exception {
        String coupon = promoCoupon("ALL", 0, 1);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var c = asBuyer(() -> confirm(q));
        var gate = new PricingGate();
        sessions.getConfiguration().addInterceptor(gate);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var placing = threads.submit(() -> asBuyer(() -> checkout.place(place(q, c))));
            assertThat(gate.entered.await(15, TimeUnit.SECONDS))
                    .as("actual pricing after coupon root lock")
                    .isTrue();
            var worker = new AtomicReference<Thread>();
            var migrating =
                    threads.submit(
                            () -> {
                                worker.set(Thread.currentThread());
                                return drain();
                            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean blocked = false;
                while (System.nanoTime() < deadline && !migrating.isDone()) {
                    var stack =
                            worker.get() == null
                                    ? List.<StackTraceElement>of()
                                    : List.of(worker.get().getStackTrace());
                    blocked =
                            stack.stream().anyMatch(f -> f.getMethodName().equals("lockPromo"))
                                    && stack.stream()
                                            .anyMatch(
                                                    f ->
                                                            f.getClassName()
                                                                            .equals(
                                                                                    "sun.nio.ch.Net")
                                                                    && f.getMethodName()
                                                                            .equals("poll"));
                    if (blocked) {
                        break;
                    }
                    Thread.sleep(10);
                }
                if (migrating.isDone()) {
                    migrating.get();
                }
                assertThat(blocked).as("real migration transition JDBC root lock wait").isTrue();
            } finally {
                gate.release.countDown();
            }
            assertThat(placing.get(15, TimeUnit.SECONDS).phase()).isEqualTo("CREATED");
            assertThat(migrating.get(15, TimeUnit.SECONDS).state()).isEqualTo("DRAINING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM pmt_user_coupon WHERE user_coupon_no=?",
                                    String.class,
                                    coupon))
                    .isEqualTo("USED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                    Long.class,
                                    buyer))
                    .isEqualTo(1);
        } finally {
            gate.enabled = false;
            gate.release.countDown();
        }
    }

    private LegacyCouponSourceFenceService.FenceView drain() {
        return fences.transition(
                new EntitlementContext("MAIN", actor, "checkout-drain-" + sku, "a".repeat(64)),
                new SourceIdentity(SourceSystem.PMT, SourceKind.TEMPLATE, "CT-" + sku),
                new Command(
                        Action.BEGIN_DRAIN,
                        "batch-" + sku,
                        0,
                        "checkout migration race",
                        "drain-" + sku));
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {
                        MappedStatement.class,
                        Object.class,
                        RowBounds.class,
                        ResultHandler.class
                    }))
    static class PricingGate implements Interceptor {
        volatile boolean enabled = true;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean captured = new AtomicBoolean();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            boolean pricing =
                    enabled
                            && statement.endsWith("ProductMappers$SkuMapper.selectList")
                            && Arrays.stream(Thread.currentThread().getStackTrace())
                                    .anyMatch(
                                            frame ->
                                                    frame.getMethodName().equals("checkoutPricing"))
                            && TransactionSynchronizationManager.isActualTransactionActive();
            Object actual = invocation.proceed();
            if (pricing && captured.compareAndSet(false, true)) {
                entered.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("pricing gate release timeout");
                }
            }
            return actual;
        }
    }

    @MockitoSpyBean private LogisticsPort logistics;
    @MockitoSpyBean private LegacyCouponUsePort payCoupon;
    @MockitoSpyBean private EntitlementOwnerRegistryPort registry;
    @MockitoSpyBean private AppointmentSlotPort appointments;

    @Test
    void ordinaryCouponCheckoutNeverCallsPayRoutingLogisticsOrAppointmentBoundary() {
        String coupon = promoCoupon("ALL", 0, 1);
        Mockito.clearInvocations(logistics, payCoupon, registry, appointments);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var c = asBuyer(() -> confirm(q));
        assertThat(asBuyer(() -> checkout.place(place(q, c))).phase()).isEqualTo("CREATED");
        Mockito.verifyNoInteractions(logistics, payCoupon, registry, appointments);
    }
}
