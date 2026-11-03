package ai.neargo.commerce.api.common;


public record StoreSnapshot(StoreRef store, String merchantNo, String businessTypeCode, StoreStatus status, CommerceModel model) {}
