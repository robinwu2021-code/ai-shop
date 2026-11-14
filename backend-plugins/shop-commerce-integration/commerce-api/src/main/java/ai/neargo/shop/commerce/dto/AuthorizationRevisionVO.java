package ai.neargo.shop.commerce.dto;

import java.time.Instant;
import java.util.List;

/** 历史授权事实不被撤权改写；current由当前账号状态和活动修订共同决定。 */
public record AuthorizationRevisionVO(
        String authorizationNo,
        long revision,
        List<String> grantedScopes,
        Instant authorizedAt,
        Instant expiresAt,
        String status,
        boolean current) {}
