package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import java.time.Instant;
import java.util.List;

/** Brand membership is frozen by its owner so issuing never substitutes a new audience during recovery. */
public interface BrandAudienceSnapshotPort {
    /** Trusted scope assembled after the management entry has checked the caller's promotion permissions. */
    record Context(String tenantNo, String managementNo, String brandNo, String legalEntityNo, String actorNo) { }

    /** Counts members that cannot be represented by a canonical recipient account. */
    record Skip(String reason, int count) { }

    /** The immutable original audience remains readable after a command has claimed it. */
    record Snapshot(String snapshotNo, int plannedCount, int preSkippedCount, List<Skip> preSkipReasons,
                    List<Recipient> recipients, Instant expiresAt, String claimedCommandNo) {
        public Snapshot {
            preSkipReasons = List.copyOf(preSkipReasons);
            recipients = List.copyOf(recipients);
        }
    }

    /** Captures all current brand members, independent of their source store's legal entity, for ten minutes. */
    Snapshot freeze(Context context, String resourceNo, String resourceVersion);

    /** Reads only the exact caller, brand, issuer and resource scope without changing the captured audience. */
    Snapshot get(Context context, String snapshotNo, String resourceNo, String resourceVersion);

    /** Binds the original command atomically; retries recover its original audience without reselecting members. */
    Snapshot claim(Context context, String snapshotNo, String resourceNo, String resourceVersion, String commandNo);
}
