package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.lifecycle.PointsAccountControlService;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy.Interval;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Command;

import java.util.List;
import java.util.Map;

/**
 * Current metadata reads participate in the same lock order and pay transaction as lifecycle
 * posting.
 */
public interface LifecycleStore {
    Map<String, Object> lockLifecycle(String typeCode, String subOrderNo);

    Map<String, Object> lockUser(String typeCode, String userNo);

    Map<String, Object> lockTransfer(String no);

    Map<String, Object> lockSwitch(String scope, String key);

    void saveSwitch(Command command, String key, String payload, boolean previouslyEnabled);

    List<Interval> pauses(String typeCode, String userNo, long lastActive, long asOf);

    void freeze(
            PointsAccountControlService.Command command,
            String payload,
            long accountVersion,
            boolean previouslyFrozen);

    void markReminded(String typeCode, String userNo, long revision);

    boolean clearForRetirement(String typeCode);
}
