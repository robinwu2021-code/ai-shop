package ai.neargo.shop.trade.order.points;

/** Source-side admission policy, independent from pay's accounting lifecycle. */
public interface PointsIntegrationPolicyService {
    IntegrationPolicy policy(String storeNo, String scene);

    IntegrationPolicy change(IntegrationPolicyChange command, String actor);

    enum Mode {
        DETACHED,
        ACTIVE,
        DRAINING
    }

    record IntegrationPolicy(
            String storeNo,
            String scene,
            Mode mode,
            boolean earnEnabled,
            boolean redeemEnabled,
            long version,
            String actor,
            String reason,
            long changedAt) {}

    record IntegrationPolicyChange(
            String requestId,
            String storeNo,
            String scene,
            Mode mode,
            boolean earnEnabled,
            boolean redeemEnabled,
            long expectedVersion,
            String reason) {}

    interface DetachmentGuard {
        boolean canDetach(String storeNo);
    }
}
