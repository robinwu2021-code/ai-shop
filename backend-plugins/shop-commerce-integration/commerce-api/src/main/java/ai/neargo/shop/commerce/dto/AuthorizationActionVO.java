package ai.neargo.shop.commerce.dto;

import java.time.Instant;

/** 固定初始响应；当前结果通过operation查询，重放不再次调外部接口。 */
public record AuthorizationActionVO(String operationNo, String status, Instant expiresAt) {}
