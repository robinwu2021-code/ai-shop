package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import java.time.Clock;

/**
 * Only a complete original action/current-revision proof authorizes removing a configured source.
 */
public final class SourceWithdrawalPolicy {
    private SourceWithdrawalPolicy() {}

    public static void query(ChannelSourceWithdrawalProvider.Query q) {
        if (q == null || q.owner() == null)
            throw new IllegalArgumentException("Missing withdrawal identity");
        CommerceValidation.text(q.entityNo(), 32);
        CommerceValidation.text(q.storeNo(), 32);
        CommerceValidation.text(q.sourceRef(), 128);
        CommerceValidation.text(q.previousRevision(), 128);
    }

    public static void disposition(
            ChannelSourceWithdrawalProvider.Query q,
            ChannelSourceWithdrawalProvider.Disposition d,
            Clock clock) {
        query(q);
        if (d == null
                || !q.equals(d.query())
                || d.state() == null
                || d.verifiedAt() == null
                || d.verifiedAt().isAfter(clock.instant())
                || d.validUntil() == null
                || !d.validUntil().isAfter(clock.instant())
                || d.validUntil().isAfter(d.verifiedAt().plusSeconds(30)))
            throw new IllegalStateException("Missing original source disposition");
        CommerceValidation.text(d.ownerReceiptNo(), 128);
        if (d.state() == ChannelSourceWithdrawalProvider.State.WITHDRAWN)
            validate(q, d.withdrawal(), clock);
        else if (d.withdrawal() != null)
            throw new IllegalStateException("Active source cannot carry a withdrawal");
    }

    public static void validate(
            ChannelSourceWithdrawalProvider.Query q,
            ChannelSourceWithdrawalProvider.Facts f,
            Clock clock) {
        query(q);
        if (f == null
                || f.data() == null
                || !q.equals(f.data().query())
                || !PricingSnapshotHash.of(f.data()).equals(f.contentHash())
                || f.data().previousContentHash() == null
                || !f.data().previousContentHash().matches("[a-f0-9]{64}")
                || f.data().originalActionHash() == null
                || !f.data().originalActionHash().matches("[a-f0-9]{64}")
                || f.data().withdrawnAt() == null
                || f.data().withdrawnAt().isAfter(clock.instant())
                || f.verifiedAt() == null
                || f.verifiedAt().isAfter(clock.instant())
                || f.validUntil() == null
                || !f.validUntil().isAfter(clock.instant())
                || f.validUntil().isAfter(f.verifiedAt().plusSeconds(30)))
            throw new IllegalStateException("Invalid original withdrawal proof");
        CommerceValidation.text(f.data().withdrawalRevision(), 128);
        CommerceValidation.text(f.data().originalActionRef(), 128);
        CommerceValidation.text(f.ownerReceiptNo(), 128);
    }
}
