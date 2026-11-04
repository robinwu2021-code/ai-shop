package ai.neargo.commerce.api.port;

/** Quantity is this operation's units; operationNo is immutable across all retries. */
public record InventoryCommand(
        String tenantNo,
        String orderNo,
        String orderComponentNo,
        String skuNo,
        long quantity,
        String operationNo,
        String carrierCode,
        String trackingNo) {
    public InventoryCommand {
        if (quantity <= 0
                || quantity > Integer.MAX_VALUE
                || operationNo == null
                || operationNo.isBlank())
            throw new IllegalArgumentException("invalid inventory quantity or operation");
    }

    public InventoryCommand(String t, String o, String c, String s, long q) {
        this(t, o, c, s, q, c, null, null);
    }

    public InventoryCommand(String t, String o, String c, String s, long q, String op) {
        this(t, o, c, s, q, op, null, null);
    }
}
