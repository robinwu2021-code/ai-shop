package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.impl.ServiceSourceObservation;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Real local service/Mapper/MySQL; the external owner is an explicit controlled contract fixture.
 */
class ServiceSourceObservationIT extends BeautyExecutionFixture {
    final ServiceSourceObservation.Canonical canonical =
            new ServiceSourceObservation.Canonical("E", "S", "U", "OP", "ORD", "SUB");
    final AtomicInteger sourceCalls = new AtomicInteger();
    IntFunction<CheckoutPort.CheckoutSourceFacts> replies = call -> complete(1, 2, "a");

    @BeforeEach
    void database() throws Exception {
        initialize("BtySourceObservation");
    }

    CheckoutPort owner() {
        return (CheckoutPort)
                Proxy.newProxyInstance(
                        CheckoutPort.class.getClassLoader(),
                        new Class<?>[] {CheckoutPort.class},
                        (proxy, method, args) -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            if ("source".equals(method.getName())) {
                                return replies.apply(sourceCalls.incrementAndGet());
                            }
                            throw new AssertionError(
                                    "Unexpected owner write/read: " + method.getName());
                        });
    }

    CheckoutPort.CheckoutSourceFacts complete(long revision, int remaining, String hash) {
        return new CheckoutPort.CheckoutSourceFacts(
                new CheckoutPort.OperationRef("BEAUTY", "OP"),
                "ORD",
                "SUB",
                "U",
                "E",
                "S",
                revision,
                hash.repeat(64),
                "PAID",
                "CNY",
                2000,
                2000L,
                0L,
                "PAID",
                List.of(
                        new CheckoutPort.SourceLine(
                                "LINE", "REF", "GOODS", "SKU", 2, remaining, "COUNT", 2000)),
                List.of(
                        new CheckoutPort.PaymentFact(
                                "PO",
                                "PAY",
                                "OUT",
                                "CASH",
                                "TRADE",
                                "SUCCESS",
                                2000,
                                "CNY",
                                "RC",
                                Instant.parse("2026-10-24T00:00:00Z"))),
                "NOT_STARTED",
                Instant.parse("2026-10-24T00:00:00Z"));
    }

    @Test
    void completeFactsEstablishObservedReadinessWithoutOwnerIoUnderLocks() {
        runner().withBean(CheckoutPort.class, this::owner)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var service =
                                    new ServiceSourceObservation(
                                            context.getBean(
                                                    BeautyMappers.SourceObservationMapper.class),
                                            context.getBeanProvider(CheckoutPort.class),
                                            context.getBean(PlatformTransactionManager.class),
                                            new ObjectMapper(),
                                            Clock.systemUTC());
                            var result = service.observe(canonical, "ACTOR");
                            assertThat(result.ready())
                                    .as("complete owner facts permit observed local allocation")
                                    .isTrue();
                            assertThat(sourceCalls).hasValue(1);
                            assertThat(result.row().getVerifiedLines())
                                    .doesNotContain("dueMinor", "2000", "currency");
                            assertThat(result.row().getSourceRevision()).isEqualTo(1);
                            assertThat(result.row().getVerifiedRevision()).isEqualTo(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_source_observation",
                                                    Integer.class))
                                    .isOne();
                        });
    }

    private ServiceSourceObservation service(ApplicationContext context) {
        return new ServiceSourceObservation(
                context.getBean(BeautyMappers.SourceObservationMapper.class),
                context.getBeanProvider(CheckoutPort.class),
                context.getBean(PlatformTransactionManager.class),
                new ObjectMapper(),
                Clock.systemUTC());
    }

    private CheckoutPort.CheckoutSourceFacts unknown(long revision, String hash) {
        return new CheckoutPort.CheckoutSourceFacts(
                new CheckoutPort.OperationRef("BEAUTY", "OP"),
                "ORD",
                "SUB",
                "U",
                "E",
                "S",
                revision,
                hash == null ? null : hash.repeat(64),
                null,
                "CNY",
                0,
                null,
                null,
                "UNKNOWN",
                List.of(),
                List.of(),
                "BLOCKED",
                Instant.now());
    }

    @Test
    void refundBelowHeldQuantityBlocksLateOldObservation() {
        seed(
                "bty_source_allocation_guard",
                "guard_no",
                "G",
                "source_kind",
                "STORE_ORDER",
                "source_no",
                "SUB",
                "source_line_ref",
                "LINE",
                "source_revision",
                1L,
                "source_qty",
                2,
                "allocated_qty",
                1,
                "uom",
                "COUNT",
                "source_hash",
                "a".repeat(64));
        replies = call -> call == 2 ? complete(2, 0, "b") : complete(1, 2, "a");
        runner().withBean(CheckoutPort.class, this::owner)
                .run(
                        context -> {
                            var service = service(context);
                            assertThat(service.observe(canonical, "ACTOR").ready()).isTrue();
                            service.observe(canonical, "ACTOR");
                            var regressed = service.observe(canonical, "ACTOR");
                            assertThat(regressed.row().getState()).isEqualTo("BLOCKED");
                            assertThat(regressed.row().getSourceRevision()).isEqualTo(2);
                            assertThat(regressed.row().getVerifiedRevision()).isEqualTo(2);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT allocated_qty FROM"
                                                            + " bty_source_allocation_guard",
                                                    BigDecimal.class))
                                    .isEqualByComparingTo("1");
                        });
    }

    @Test
    void sameRevisionDifferentHashAndForeignOperationBlock() {
        replies = call -> complete(1, 2, call == 1 ? "a" : "b");
        runner().withBean(CheckoutPort.class, this::owner)
                .run(
                        context -> {
                            var service = service(context);
                            assertThat(service.observe(canonical, "ACTOR").ready()).isTrue();
                            var conflict = service.observe(canonical, "ACTOR");
                            assertThat(conflict.row().getState()).isEqualTo("BLOCKED");
                            assertThat(conflict.row().getSourceHash()).isEqualTo("a".repeat(64));
                            var complete = complete(99, 2, "c");
                            replies =
                                    call ->
                                            new CheckoutPort.CheckoutSourceFacts(
                                                    new CheckoutPort.OperationRef(
                                                            "BEAUTY", "FOREIGN"),
                                                    complete.orderNo(),
                                                    complete.subOrderNo(),
                                                    complete.buyerNo(),
                                                    complete.entityNo(),
                                                    complete.storeNo(),
                                                    complete.sourceRevision(),
                                                    complete.sourceHash(),
                                                    complete.orderStatus(),
                                                    complete.currency(),
                                                    complete.dueMinor(),
                                                    complete.receivedMinor(),
                                                    complete.outstandingMinor(),
                                                    complete.financialState(),
                                                    complete.lines(),
                                                    complete.payments(),
                                                    complete.executionState(),
                                                    complete.observedAt());
                            var foreign = service.observe(canonical, "ACTOR");
                            assertThat(foreign.ready()).isFalse();
                            assertThat(foreign.row().getSourceRevision()).isEqualTo(1);
                            assertThat(foreign.row().getSourceHash()).isEqualTo("a".repeat(64));
                        });
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void overlapping(
            CheckoutPort.CheckoutSourceFacts older,
            CheckoutPort.CheckoutSourceFacts current,
            Consumer<ServiceSourceObservation.Observed> afterOlder,
            Consumer<ServiceSourceObservation.Observed> afterCurrent) {
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        replies =
                call -> {
                    if (call == 1) {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return older;
                    }
                    secondEntered.countDown();
                    await(releaseSecond);
                    return current;
                };
        runner().withBean(CheckoutPort.class, this::owner)
                .run(
                        context -> {
                            var service = service(context);
                            try (var executor = Executors.newFixedThreadPool(2)) {
                                var first =
                                        executor.submit(() -> service.observe(canonical, "ACTOR"));
                                await(firstEntered);
                                var second =
                                        executor.submit(() -> service.observe(canonical, "ACTOR"));
                                await(secondEntered);
                                releaseFirst.countDown();
                                try {
                                    afterOlder.accept(first.get(15, TimeUnit.SECONDS));
                                    releaseSecond.countDown();
                                    afterCurrent.accept(second.get(15, TimeUnit.SECONDS));
                                } catch (ExecutionException
                                        | TimeoutException
                                        | InterruptedException failure) {
                                    throw new AssertionError(failure);
                                } finally {
                                    releaseFirst.countDown();
                                    releaseSecond.countDown();
                                }
                            }
                        });
    }

    @Test
    void supersededHigherRevisionBlocksDelayedCurrentLowerFacts() {
        overlapping(
                complete(2, 0, "b"),
                complete(1, 2, "a"),
                old -> {
                    assertThat(old.row().getState()).isEqualTo("BLOCKED");
                    assertThat(old.row().getSourceRevision()).isEqualTo(2);
                    assertThat(old.row().getVerifiedRevision()).isNull();
                },
                current -> {
                    assertThat(current.ready()).isFalse();
                    assertThat(current.row().getState()).isEqualTo("BLOCKED");
                    assertThat(current.row().getSourceRevision()).isEqualTo(2);
                });
    }

    @Test
    void supersededTrustedUnknownWithHigherPairInvalidatesCurrentRead() {
        overlapping(
                unknown(2, "b"),
                complete(1, 2, "a"),
                old -> {
                    assertThat(old.row().getState()).isEqualTo("UNKNOWN");
                    assertThat(old.row().getSourceRevision()).isEqualTo(2);
                    assertThat(old.row().getObservationEpoch()).isEqualTo(3);
                },
                current -> {
                    assertThat(current.ready()).isFalse();
                    assertThat(current.row().getSourceRevision()).isEqualTo(2);
                });
    }

    @Test
    void supersededTrustedUnknownWithoutPairRequiresFreshRead() {
        overlapping(
                unknown(0, null),
                complete(1, 2, "a"),
                old -> {
                    assertThat(old.row().getObservationEpoch()).isEqualTo(3);
                    assertThat(old.row().getSourceRevision()).isNull();
                },
                current -> {
                    assertThat(current.ready()).isFalse();
                    assertThat(current.row().getState()).isEqualTo("BLOCKED");
                });
    }

    @Test
    void unknownWatermarkCannotBeClearedByOlderEpoch() {
        var oldEntered = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        replies =
                call -> {
                    if (call == 2) {
                        oldEntered.countDown();
                        await(releaseOld);
                    }
                    return call == 3 ? unknown(1, "a") : complete(1, 2, "a");
                };
        runner().withBean(CheckoutPort.class, this::owner)
                .run(
                        context -> {
                            var service = service(context);
                            assertThat(service.observe(canonical, "ACTOR").ready()).isTrue();
                            try (var executor = Executors.newSingleThreadExecutor()) {
                                var old =
                                        executor.submit(() -> service.observe(canonical, "ACTOR"));
                                await(oldEntered);
                                assertThat(service.observe(canonical, "ACTOR").ready()).isFalse();
                                releaseOld.countDown();
                                try {
                                    assertThat(old.get(15, TimeUnit.SECONDS).row().getState())
                                            .isEqualTo("UNKNOWN");
                                    assertThat(service.observe(canonical, "ACTOR").ready())
                                            .isTrue();
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                } finally {
                                    releaseOld.countDown();
                                }
                            }
                        });
    }
}
