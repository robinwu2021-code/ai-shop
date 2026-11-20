package ai.neargo.shop.paybridge;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
/** Internal checkout/recovery orchestration. No public endpoint trusts caller-supplied sale units. */
public interface CardSaleCoordinator {
    Progress recover(EntitlementContext context, String orderNo);
    record Progress(int total, int reserved, int issued, int released, int pending) {
        public boolean readyForPayment() { return total > 0 && reserved == total; }
    }
}
