package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Challenge;
import ai.neargo.shop.food.dto.OfflineDraftContracts.ChallengeRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Enroll;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Lease;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Receipt;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Replay;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Signer;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Upload;
import ai.neargo.shop.food.service.OfflineDraftService;
import ai.neargo.shop.food.spi.OfflineDraftInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/**
 * Remote transport returns original FOOD receipts and never grants drafts when the owner is absent.
 */
public final class RemoteOfflineDraftService implements OfflineDraftService {
    private final OfflineDraftInternalApi api;

    public RemoteOfflineDraftService(OfflineDraftInternalApi api) {
        this.api = api;
    }

    @Override
    public Challenge challenge(String e, String s, String d, ChallengeRequest r) {
        var v = call(() -> api.challenge(new OfflineDraftInternalApi.ChallengeCommand(e, s, d, r)));
        check(
                v != null
                        && e.equals(v.entityNo())
                        && s.equals(v.storeNo())
                        && d.equals(v.deviceNo())
                        && PricingSnapshotHash.of(r.publicKey()).equals(v.publicKeyHash())
                        && v.challengeNo() != null
                        && v.validUntil() != null);
        return v;
    }

    @Override
    public Signer enroll(String e, String s, String d, Enroll r) {
        var v = call(() -> api.enroll(new OfflineDraftInternalApi.EnrollCommand(e, s, d, r)));
        check(
                v != null
                        && e.equals(v.entityNo())
                        && s.equals(v.storeNo())
                        && d.equals(v.deviceNo())
                        && v.keyNo() != null);
        return v;
    }

    @Override
    public Lease state(String e, String s, String d, ai.neargo.shop.food.dto.CartAccess access) {
        var result =
                call(() -> api.state(new OfflineDraftInternalApi.StateCommand(e, s, d, access)));
        check(
                result != null
                        && e.equals(result.entityNo())
                        && s.equals(result.storeNo())
                        && "DRAFT_CAPTURE_ONLY".equals(result.capability()));
        return result;
    }

    @Override
    public Lease acquire(String e, String s, String d, LeaseRequest r) {
        var v = call(() -> api.acquire(new OfflineDraftInternalApi.LeaseCommand(e, s, d, r)));
        check(
                v != null
                        && e.equals(v.entityNo())
                        && s.equals(v.storeNo())
                        && d.equals(v.deviceNo())
                        && r.keyNo().equals(v.keyNo())
                        && v.fence() == Math.addExact(r.expectedFence(), 1)
                        && "DRAFT_CAPTURE_ONLY".equals(v.capability()));
        return v;
    }

    @Override
    public Receipt capture(String e, String s, String d, Upload r) {
        var v = call(() -> api.capture(new OfflineDraftInternalApi.CaptureCommand(e, s, d, r)));
        check(
                v != null
                        && v.receiptNo() != null
                        && PricingSnapshotHash.of(r.envelope()).equals(v.payloadHash()));
        return v;
    }

    @Override
    public Receipt replay(String e, String s, String d, Replay r) {
        var v = call(() -> api.replay(new OfflineDraftInternalApi.ReplayCommand(e, s, d, r)));
        check(v != null && r.draftNo().equals(v.draftNo()) && v.receiptNo() != null);
        return v;
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, action);
        } catch (ServiceCallException unavailable) {
            throw unavailable;
        }
    }

    private static void check(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.CONFLICT);
    }
}
