package ai.neargo.shop.print.agent;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class AgentDurabilityTest {
    @TempDir Path temp;
    final AgentJson json = new AgentJson();

    ClaimDispatch dispatch() {
        var pages = List.<PrintPage>of();
        var layout =
                new PrintLayout(
                        "ROLL58_V1",
                        "NOTO_SC_V1",
                        PrintHashes.sha256("font"),
                        BigDecimal.valueOf(58),
                        pages,
                        json.freeze(pages).sha256());
        return new ClaimDispatch(
                "D1",
                "J1",
                "A1",
                "P1",
                1,
                1,
                1,
                "lease",
                Instant.now().plusSeconds(120),
                Instant.now().plusSeconds(120),
                layout,
                PrintHashes.sha256("document"),
                json.freeze(Map.of("queueName", "TEST")),
                false);
    }

    LocalEvent event(ObservedPrintStatus status, long sequence) {
        return new LocalEvent(
                "E" + sequence,
                "D1",
                "A1",
                1,
                1,
                sequence,
                status,
                PrintEvidenceLevel.NONE,
                json.freeze(Map.of("queueTerminated", false, "partialOutput", false)),
                Instant.now(),
                null);
    }

    @Test
    void commitBeforeQueueIsDurableAndDuplicateReceiveNeverResubmits() {
        Path file = temp.resolve("agent.db");
        var store = new SqliteLocalDispatchStore(file);
        store.receiveAndCommit(dispatch());
        assertEquals(0, store.find("D1").orElseThrow().revision());
        store.markSubmittingAndAppendEvent("D1", 0, event(ObservedPrintStatus.SUBMITTING, 1));
        var reopened = new SqliteLocalDispatchStore(file);
        assertEquals(ObservedPrintStatus.SUBMITTING, reopened.find("D1").orElseThrow().status());
        assertEquals(1, reopened.pendingEvents(100).size());
        reopened.receiveAndCommit(dispatch());
        assertEquals(ObservedPrintStatus.SUBMITTING, reopened.find("D1").orElseThrow().status());
        assertThrows(
                IllegalStateException.class,
                () ->
                        reopened.markSubmittingAndAppendEvent(
                                "D1", 1, event(ObservedPrintStatus.SUBMITTING, 2)));
    }

    @Test
    void failedEventCommitRollsBackSubmittingState() {
        var store = new SqliteLocalDispatchStore(temp.resolve("rollback.db"));
        store.receiveAndCommit(dispatch());
        var obs =
                new QueueObservation(
                        ObservedPrintStatus.RECEIVED,
                        null,
                        false,
                        false,
                        PrintEvidenceLevel.NONE,
                        json.freeze(Map.of()));
        store.recordObservationAndAppendEvent("D1", obs, event(ObservedPrintStatus.RECEIVED, 1));
        long revision = store.find("D1").orElseThrow().revision();
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.markSubmittingAndAppendEvent(
                                "D1", revision, event(ObservedPrintStatus.SUBMITTING, 1)));
        assertEquals(ObservedPrintStatus.RECEIVED, store.find("D1").orElseThrow().status());
    }

    @Test
    void restartAfterSubmittingOnlyQueriesNeverSubmits() {
        var store = new SqliteLocalDispatchStore(temp.resolve("restart.db"));
        store.receiveAndCommit(dispatch());
        store.markSubmittingAndAppendEvent("D1", 0, event(ObservedPrintStatus.SUBMITTING, 1));
        int[] calls = {0, 0};
        DesktopPrintExecutor executor =
                new DesktopPrintExecutor() {
                    public QueueObservation submit(LocalDispatch d) {
                        calls[0]++;
                        throw new AssertionError("MUST_NOT_RESUBMIT");
                    }

                    public QueueObservation query(LocalDispatch d) {
                        calls[1]++;
                        return new QueueObservation(
                                ObservedPrintStatus.UNKNOWN,
                                null,
                                false,
                                false,
                                PrintEvidenceLevel.NONE,
                                json.freeze(
                                        Map.of("queueTerminated", false, "partialOutput", false)));
                    }

                    public QueueObservation cancel(LocalDispatch d) {
                        throw new AssertionError();
                    }
                };
        new AgentRecoveryCoordinator(store, executor)
                .recoverOnStartup(new PrintWorkerBudget(10, Duration.ofSeconds(5), "acceptance"));
        assertArrayEquals(new int[] {0, 1}, calls);
        assertEquals(ObservedPrintStatus.UNKNOWN, store.find("D1").orElseThrow().status());
        assertEquals(2, store.pendingEvents(100).size());
    }

    @Test
    void acknowledgedEventsSurviveRestartAndSequenceNeverResets() {
        Path file = temp.resolve("sequence.db");
        var store = new SqliteLocalDispatchStore(file);
        store.receiveAndCommit(dispatch());
        store.markSubmittingAndAppendEvent("D1", 0, event(ObservedPrintStatus.SUBMITTING, 1));
        store.markEventsAcknowledged(Set.of("E1"));
        var reopened = new SqliteLocalDispatchStore(file);
        assertTrue(reopened.pendingEvents(100).isEmpty());
        assertEquals(2, reopened.nextSequence("D1"));
    }

    @Test
    void staleCancellationCannotOverwriteCommittedSubmission() throws Exception {
        var store = new SqliteLocalDispatchStore(temp.resolve("cancel-race.db"));
        store.receiveAndCommit(dispatch());
        var stale = store.find("D1").orElseThrow();
        var submitted = new java.util.concurrent.CountDownLatch(1);
        var cancelReady = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var cancel =
                    pool.submit(
                            () -> {
                                cancelReady.countDown();
                                try {
                                    submitted.await();
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                                try {
                                    store.recordObservationAndAppendEvent(
                                            stale.dispatchKey(),
                                            new QueueObservation(
                                                    ObservedPrintStatus.CANCELLED,
                                                    null,
                                                    true,
                                                    false,
                                                    PrintEvidenceLevel.NONE,
                                                    json.freeze(
                                                            Map.of(
                                                                    "queueTerminated",
                                                                    true,
                                                                    "partialOutput",
                                                                    false))),
                                            event(ObservedPrintStatus.CANCELLED, 2));
                                } catch (IllegalStateException rejected) {
                                }
                            });
            assertTrue(cancelReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
            store.markSubmittingAndAppendEvent("D1", 0, event(ObservedPrintStatus.SUBMITTING, 1));
            submitted.countDown();
            cancel.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(ObservedPrintStatus.SUBMITTING, store.find("D1").orElseThrow().status());
            assertTrue(
                    store.pendingEvents(10).stream()
                            .noneMatch(e -> e.status() == ObservedPrintStatus.CANCELLED));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void atomicCancellationWinsBeforeSubmittingAndLosesAfterSubmitting() {
        var store = new SqliteLocalDispatchStore(temp.resolve("atomic-cancel.db"));
        store.receiveAndCommit(dispatch());
        assertTrue(
                store.cancelBeforeSubmittingAndAppendEvent(
                        "D1", 0, event(ObservedPrintStatus.CANCELLED, 1)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.markSubmittingAndAppendEvent(
                                "D1", 1, event(ObservedPrintStatus.SUBMITTING, 2)));
        var other = new SqliteLocalDispatchStore(temp.resolve("atomic-submit.db"));
        other.receiveAndCommit(dispatch());
        other.markSubmittingAndAppendEvent("D1", 0, event(ObservedPrintStatus.SUBMITTING, 1));
        assertFalse(
                other.cancelBeforeSubmittingAndAppendEvent(
                        "D1", 0, event(ObservedPrintStatus.CANCELLED, 2)));
        assertEquals(ObservedPrintStatus.SUBMITTING, other.find("D1").orElseThrow().status());
    }
}
