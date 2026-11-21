package ai.neargo.shop.print.api;
import java.util.Set;
/** Source-owner read permissions frozen into every real document, including historical exports. */
public final class PrintSourceDataScopes {
    private PrintSourceDataScopes() {}
    public static Set<String> requiredForScene(String scene) {
        return required(switch(scene) { case "REFUND_RECEIPT" -> "REFUND"; case "RECHARGE_RECEIPT" -> "RECHARGE"; case "INVOICE_PROOF" -> "INVOICE"; default -> "SUB_ORDER"; });
    }
    public static boolean hasAnySource(Set<String> scopes) {
        return scopes.contains("SOURCE_ORDER_READ") || scopes.contains("SOURCE_REFUND_READ") || scopes.contains("SOURCE_RECHARGE_READ")
                || scopes.contains("SOURCE_INVOICE_READ");
    }
    public static Set<String> required(String sourceType) {
        return switch(sourceType) {
            case "RECHARGE" -> Set.of("SOURCE_RECHARGE_READ");
            case "REFUND" -> Set.of("SOURCE_REFUND_READ");
            case "INVOICE" -> Set.of("SOURCE_INVOICE_READ");
            default -> Set.of("SOURCE_ORDER_READ");
        };
    }
}
