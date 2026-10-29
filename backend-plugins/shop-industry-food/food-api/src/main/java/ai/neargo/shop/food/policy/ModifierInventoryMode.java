package ai.neargo.shop.food.policy;

/** Resolves a frozen modifier's physical stock authority. */
public final class ModifierInventoryMode {
    private ModifierInventoryMode() {}

    public static String resolve(String kind, String salesSkuNo, String configuredMode) {
        if (!"ADDON".equals(kind) && !"COMBO".equals(kind))
            throw new IllegalArgumentException("Invalid modifier kind");
        if (salesSkuNo != null && salesSkuNo.isBlank())
            throw new IllegalArgumentException("Invalid sales SKU");
        if (configuredMode == null) {
            if (salesSkuNo != null) return "SALES_SKU";
            return "COMBO".equals(kind) ? "COMPONENTS" : "NONE";
        }
        if ("SALES_SKU".equals(configuredMode) && salesSkuNo != null) return configuredMode;
        if ("COMPONENTS".equals(configuredMode) && "COMBO".equals(kind)) return configuredMode;
        throw new IllegalArgumentException("Invalid modifier inventory mode");
    }
}
