package ai.neargo.shop.paybridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@EnabledIfEnvironmentVariable(named = "PAYMENT_SSE_JDBC_URL", matches = "jdbc:mysql://127.*")
class PaymentCollectionSlowWriterMysqlTest {
    static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final boolean blockPayment;
        final CountDownLatch blocked = new CountDownLatch(1), aborted = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean release;

        RecordingEmitter(boolean blockPayment) {
            super(0L);
            this.blockPayment = blockPayment;
        }

        @Override
        public void send(SseEventBuilder frame) throws IOException {
            String text =
                    frame.build().stream()
                            .map(part -> String.valueOf(part.getData()))
                            .collect(java.util.stream.Collectors.joining());
            frames.add(text);
            if (blockPayment && text.contains("payment.succeeded")) {
                blocked.countDown();
                // Model a socket write that does not promptly cooperate with interruption.
                while (!release) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    Thread.interrupted();
                }
            }
        }

        @Override
        public void completeWithError(Throwable error) {
            failure.compareAndSet(null, error);
            aborted.countDown();
        }

        @Override
        public void complete() {}

        void awaitFrame(String content) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (frames.stream().noneMatch(s -> s.contains(content)) && System.nanoTime() < until)
                Thread.sleep(10);
            assertTrue(
                    frames.stream().anyMatch(s -> s.contains(content)),
                    content + " was not delivered");
        }
    }

    @Test
    void blockedWriterDoesNotBlockHealthyFramesRevocationOrItsOwnDeadlineAndQueueBudget()
            throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            var blocked = new RecordingEmitter(true);
            var healthy = new RecordingEmitter(false);
            f.properties.setWriteTimeoutMs(1000);
            var next = new AtomicInteger();
            try (var stream =
                    new PaymentCollectionStream(
                            f.ds,
                            f.properties,
                            f.security,
                            f.identities,
                            f.tokens,
                            f.projection,
                            Clock.systemUTC(),
                            () -> next.getAndIncrement() == 0 ? blocked : healthy)) {
                var clerk = LoginUser.merchantByUser("USR_CLERK", "clerk");
                String clerkToken = f.tokens.issue(TokenStore.SessionData.of(clerk));
                stream.open(
                        f.user,
                        f.identities.resolveStrict(f.user, "ST_SSE_SANDBOX"),
                        f.token,
                        null);
                stream.open(
                        clerk,
                        f.identities.resolveStrict(clerk, "ST_SSE_SANDBOX"),
                        clerkToken,
                        null);
                blocked.awaitFrame("ready");
                healthy.awaitFrame("ready");
                var receipt = f.receive(51);
                f.projection.consume(f.work(receipt.sourceReceiptNo()));
                assertTrue(blocked.blocked.await(5, TimeUnit.SECONDS));
                healthy.awaitFrame("P51");
                f.tokens.revoke(clerkToken);
                healthy.awaitFrame("access_revoked");
                assertTrue(blocked.aborted.await(5, TimeUnit.SECONDS));
                assertTrue(blocked.failure.get().getMessage().contains("deadline"));
                assertNull(healthy.failure.get());
            } finally {
                blocked.release = true;
            }

            var saturated = new RecordingEmitter(true);
            var stillHealthy = new RecordingEmitter(false);
            f.properties.setWriteTimeoutMs(10000);
            f.properties.setSendQueueCapacity(2);
            f.properties.setHeartbeatMs(1000);
            var again = new AtomicInteger();
            try (var stream =
                    new PaymentCollectionStream(
                            f.ds,
                            f.properties,
                            f.security,
                            f.identities,
                            f.tokens,
                            f.projection,
                            Clock.systemUTC(),
                            () -> again.getAndIncrement() == 0 ? saturated : stillHealthy)) {
                var ctx = f.identities.resolveStrict(f.user, "ST_SSE_SANDBOX");
                stream.open(f.user, ctx, f.token, null);
                stream.open(f.user, ctx, f.token, null);
                saturated.awaitFrame("ready");
                stillHealthy.awaitFrame("ready");
                var r = f.receive(52);
                f.projection.consume(f.work(r.sourceReceiptNo()));
                assertTrue(saturated.blocked.await(5, TimeUnit.SECONDS));
                stillHealthy.awaitFrame("P52");
                r = f.receive(53);
                f.projection.consume(f.work(r.sourceReceiptNo()));
                stillHealthy.awaitFrame("P53");
                assertTrue(saturated.aborted.await(5, TimeUnit.SECONDS));
                assertTrue(saturated.failure.get().getMessage().contains("queue full"));
                assertNull(stillHealthy.failure.get());
            } finally {
                saturated.release = true;
            }
        }
    }

    @Test
    void alreadyCompletingHttpAbortDoesNotEscapeItsIndependentCleanupThread() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            var ready = new CountDownLatch(1);
            var attempted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var httpCompleted = new java.util.concurrent.atomic.AtomicBoolean();
            var escaped = new AtomicReference<Throwable>();
            var abortThread = new AtomicReference<Thread>();
            var emitter =
                    new SseEmitter(0L) {
                        @Override
                        public void send(SseEventBuilder frame) {
                            ready.countDown();
                        }

                        @Override
                        public void completeWithError(Throwable error) {
                            Thread current = Thread.currentThread();
                            current.setUncaughtExceptionHandler(
                                    (thread, failure) -> escaped.set(failure));
                            abortThread.set(current);
                            attempted.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                throw new AssertionError(interrupted);
                            }
                            assertTrue(httpCompleted.get());
                            throw new IllegalStateException(
                                    "Calling [asyncDispatch()] is not valid for a request with"
                                        + " Async state [COMPLETING]");
                        }
                    };
            try (var stream =
                    new PaymentCollectionStream(
                            f.ds,
                            f.properties,
                            f.security,
                            f.identities,
                            f.tokens,
                            f.projection,
                            Clock.systemUTC(),
                            () -> emitter)) {
                stream.open(
                        f.user,
                        f.identities.resolveStrict(f.user, "ST_SSE_SANDBOX"),
                        f.token,
                        null,
                        () -> true,
                        () -> httpCompleted.set(true));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                // close must return while Spring completion is still blocked on its own thread.
                stream.close();
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertTrue(httpCompleted.get());
                release.countDown();
                abortThread.get().join(5000);
                assertFalse(abortThread.get().isAlive());
                assertNull(escaped.get());
            } finally {
                release.countDown();
            }
        }
    }
}
