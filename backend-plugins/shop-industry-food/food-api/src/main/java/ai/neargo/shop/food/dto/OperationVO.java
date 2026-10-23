package ai.neargo.shop.food.dto;

/** Durable local command receipt; SUCCEEDED means publication committed, not that its effective time has arrived. */
public record OperationVO(String operationNo, String status, long version, String resultRef) { }
