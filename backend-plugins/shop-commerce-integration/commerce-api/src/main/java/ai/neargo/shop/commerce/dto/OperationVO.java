package ai.neargo.shop.commerce.dto;

import java.time.Instant;

/** 授权URL只在待回调且未过期时可见；不暴露nonce摘要、凭据或数据库主键。 */
public record OperationVO(
        String operationNo,
        String type,
        String status,
        String accountNo,
        String authorizationUrl,
        Instant expiresAt,
        String errorCode,
        long version) {}
