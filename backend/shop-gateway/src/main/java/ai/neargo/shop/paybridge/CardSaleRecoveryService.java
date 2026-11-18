package ai.neargo.shop.paybridge;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
public interface CardSaleRecoveryService {
    Result recover(EntitlementContext context,String afterOrderNo,int limit);
    record Result(int scanned,int completed,int failed,String nextOrderNo) { }
}
