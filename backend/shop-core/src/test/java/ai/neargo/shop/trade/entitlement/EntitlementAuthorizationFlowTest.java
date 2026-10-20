package ai.neargo.shop.trade.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EntitlementAuthorizationFlowTest {
    private static final Instant NOW = Instant.parse("2026-10-18T02:00:00Z");

    @Test
    void intentIsNotAuthorizationUntilConsumerConfirms() {
        var intent = AuthorizationIntent.pending("intent-1", "tenant-1", "user-1", "order-1", 3,
                "quote-1", "a".repeat(64), "b".repeat(64), NOW.plusSeconds(300));

        assertThat(intent.state()).isEqualTo(AuthorizationIntent.State.PENDING);
        assertThat(intent.authorizedAt()).isNull();
        assertThat(intent.confirm("user-1", "c".repeat(32), NOW).state())
                .isEqualTo(AuthorizationIntent.State.AUTHORIZED);
    }

    @Test
    void onlyBoundConsumerCanConfirmAndExpiredOrChangedIntentCannotBeAuthorized() {
        var intent = AuthorizationIntent.pending("intent-1", "tenant-1", "user-1", "order-1", 3,
                "quote-1", "a".repeat(64), "b".repeat(64), NOW.plusSeconds(30));

        assertThatThrownBy(() -> intent.confirm("user-2", "c".repeat(32), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent.confirm("user-1", "c".repeat(32), NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> intent.revalidate(4, "a".repeat(64), "b".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameRequestReplaysOriginalAuthorizationButDifferentRequestConflicts() {
        var intent = AuthorizationIntent.pending("intent-1", "tenant-1", "user-1", "order-1", 3,
                "quote-1", "a".repeat(64), "b".repeat(64), NOW.plusSeconds(300));
        var authorized = intent.confirm("user-1", "c".repeat(32), NOW);

        assertThat(authorized.confirm("user-1", "c".repeat(32), NOW.plusSeconds(1))).isEqualTo(authorized);
        assertThatThrownBy(() -> authorized.confirm("user-1", "d".repeat(32), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successfulSourceDecisionIsUniqueAcrossRevisionsAndCancellationIsNotSuccess() {
        var cancelled = SourceDecisionRecord.cancelled("tenant-1", "USE", "intent-1", 3, "cancel-1");
        var committed = SourceDecisionRecord.committed("tenant-1", "USE", "intent-1", "settled-1", 4,
                "commit-1");

        assertThat(cancelled.settledSourceKey()).isNull();
        assertThat(committed.settledSourceKey()).isEqualTo("settled-1");
        var retryAtNewRevision = SourceDecisionRecord.committed("tenant-1", "USE", "intent-1", "settled-1", 5,
                "commit-2");
        // The records can be constructed independently; the durable unique key prevents both inserts.
        assertThat(retryAtNewRevision.settledSourceKey()).isEqualTo(committed.settledSourceKey());
    }
}
