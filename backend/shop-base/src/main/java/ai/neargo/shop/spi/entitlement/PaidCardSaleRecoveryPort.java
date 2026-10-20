package ai.neargo.shop.spi.entitlement;
import java.util.List;
/** Core-owned terminal orders awaiting delivery acknowledgement, never a time-based cancellation oracle. */
public interface PaidCardSaleRecoveryPort {
    List<String> pending(EntitlementContext context,String afterOrderNo,int limit);
    void acknowledge(EntitlementContext context,String orderNo);
}
