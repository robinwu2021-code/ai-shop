package ai.neargo.inventory.api;

/**
 * 进销存的「预期内失败」出口：领域代码只抛它，不返回 null、不往正常返回值里塞错误。
 *
 * <p>参数原样带出，由展示方决定怎么拼文案（例：{@link InventoryError#STOCK_SHORT} 带缺货物料号）。
 */
public class InventoryException extends RuntimeException {

    private final InventoryError error;
    private final transient Object[] args;

    public InventoryException(InventoryError error, Object... args) {
        super(error.key());
        this.error = error;
        this.args = args;
    }

    public static InventoryException of(InventoryError error, Object... args) {
        return new InventoryException(error, args);
    }

    public InventoryError error() {
        return error;
    }

    public Object[] args() {
        return args;
    }
}
