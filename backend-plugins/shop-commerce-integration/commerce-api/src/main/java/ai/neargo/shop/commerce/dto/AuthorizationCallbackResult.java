package ai.neargo.shop.commerce.dto;

/** 协议响应不包含账号归属、临时代码、凭据或任意回跳URL。 */
public record AuthorizationCallbackResult(String operationNo, String status) {}
