package ai.neargo.shop.pay.storedvalue.policy;

import java.util.Map;

/** Maintains compatibility plans projected from immutable releases. */
public interface StoredValueReleasePlanProjectionService {
    void activate(Map<String, Object> release, String actor);

    void stop(Map<String, Object> release, String actor);
}
