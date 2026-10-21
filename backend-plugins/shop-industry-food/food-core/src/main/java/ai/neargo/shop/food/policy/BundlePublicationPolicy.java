package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;

/** Drafts can be empty; a published bundle must admit at least one nonempty valid serving selection. */
public final class BundlePublicationPolicy {
    private BundlePublicationPolicy() { }

    public static ReplaceBundleDefinitionReq validate(ReplaceBundleDefinitionReq input) {
        var value = BundleDefinitionPolicy.normalize(input);
        if (value.groups().isEmpty() || value.groups().stream().noneMatch(g -> g.maxQty().signum() > 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return value;
    }
}
