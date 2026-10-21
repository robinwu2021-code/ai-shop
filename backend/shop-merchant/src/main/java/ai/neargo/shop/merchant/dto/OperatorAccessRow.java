package ai.neargo.shop.merchant.dto;

/** At most one active typed membership may satisfy an owner query. */
public record OperatorAccessRow(String accountNo, boolean allowed) { }
