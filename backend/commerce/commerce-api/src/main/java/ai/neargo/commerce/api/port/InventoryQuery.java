package ai.neargo.commerce.api.port;

/**
 * Exact original operation; release must durably fence concurrent/later reserve for this identity.
 */
public record InventoryQuery(String tenantNo, String orderComponentNo, String operationNo) {
    public InventoryQuery(String t, String c) {
        this(t, c, c);
    }
}
