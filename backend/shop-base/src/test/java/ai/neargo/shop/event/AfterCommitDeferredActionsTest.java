package ai.neargo.shop.event;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AfterCommitDeferredActionsTest {
    private static final Object RESOURCE = new Object();
    private final TransactionTemplate transactions = new TransactionTemplate(new LocalTransactions());
    @Test void committedActionsWaitUntilTheOriginalTransactionHasReleasedItsResources() {
        List<String> completed = new ArrayList<>();
        String result = AfterCommit.withDeferredActions(() -> {
            transactions.execute(status -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                AfterCommit.run("committed", () -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(TransactionSynchronizationManager.hasResource(RESOURCE)); completed.add("done");
                });
                return null;
            });
            assertTrue(completed.isEmpty()); return "result";
        });
        assertEquals("result", result); assertEquals(List.of("done"), completed);
    }
    @Test void rollbackNeverQueuesItsActions() {
        List<String> completed = new ArrayList<>();
        AfterCommit.withDeferredActions(() -> transactions.execute(status -> {
            AfterCommit.run("rolled back", () -> completed.add("wrong")); status.setRollbackOnly(); return null;
        }));
        assertTrue(completed.isEmpty());
    }
    @Test void nestedScopesReuseTheOuterQueueAndNeverDrainEarly() {
        List<String> completed = new ArrayList<>();
        AfterCommit.withDeferredActions(() -> {
            AfterCommit.withDeferredActions(() -> transactions.execute(status -> {
                AfterCommit.run("nested", () -> completed.add("nested")); return null;
            }));
            assertTrue(completed.isEmpty()); return null;
        });
        assertEquals(List.of("nested"), completed);
    }
    @Test void committedActionsStillDrainWhenLaterOuterWorkThrowsAndTheScopeIsCleared() {
        List<String> completed = new ArrayList<>(); RuntimeException original = new RuntimeException("later failure");
        assertSame(original, assertThrows(RuntimeException.class, () -> AfterCommit.withDeferredActions(() -> {
            transactions.execute(status -> { AfterCommit.run("committed", () -> completed.add("first")); return null; });
            throw original;
        })));
        AfterCommit.withDeferredActions(() -> { AfterCommit.run("fresh", () -> completed.add("second")); return null; });
        assertEquals(List.of("first", "second"), completed);
    }
    @Test void anUnwrappedAmbientTransactionCannotPretendItHasBeenCleanedUp() {
        transactions.execute(status -> {
            assertThrows(IllegalStateException.class, () -> AfterCommit.withDeferredActions(() -> "wrong")); return null;
        });
    }
    @Test void directNonOptedInActionsKeepTheirImmediateBehavior() {
        List<String> completed = new ArrayList<>(); AfterCommit.run("direct", () -> completed.add("now"));
        assertEquals(List.of("now"), completed);
    }
    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            TransactionSynchronizationManager.bindResource(RESOURCE, new Object());
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            TransactionSynchronizationManager.unbindResource(RESOURCE);
        }
    }
}
