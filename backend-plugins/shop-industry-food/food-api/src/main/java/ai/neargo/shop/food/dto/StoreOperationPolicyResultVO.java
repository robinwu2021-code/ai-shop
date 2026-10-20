package ai.neargo.shop.food.dto;

import java.util.List;

/** Published configuration snapshot, not permission to enable an unaccepted transaction capability. */
public record StoreOperationPolicyResultVO(String policyNo, String timezone, int cutoverMinute,
        String payPolicy, List<String> capabilities, long version) {
    public StoreOperationPolicyResultVO {
        capabilities = List.copyOf(capabilities);
    }
}
