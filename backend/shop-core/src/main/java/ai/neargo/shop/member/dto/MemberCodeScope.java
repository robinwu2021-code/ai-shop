package ai.neargo.shop.member.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Trusted application scope, never deserialized from public requests. */
public record MemberCodeScope(String tenantNo, String entityNo, String storeNo, String actorNo) {
    public MemberCodeScope {
        check(tenantNo, 32);
        check(entityNo, 64);
        check(storeNo, 64);
        check(actorNo, 64);
    }

    private static void check(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
