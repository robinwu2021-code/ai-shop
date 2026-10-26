package ai.neargo.shop.trade.order.printing;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueReceiptConfirmed;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Binding;
import java.util.List;
/** Owner-confirmed immutable receipt facts; implementations never own print delivery. */
public interface BusinessPrintFactService {
    PrintFactRef recordRecharge(StoredValueReceiptConfirmed event);
    PrintFactRef recordRefund(OrdAfterSale sale,OrdSubOrder sub,OrdRefundExecution execution);
    PrintFactRef recordPointsRefund(OrdAfterSale sale,OrdSubOrder sub,RefundPlan plan,Binding binding);
    List<PrintFactRef> referencesByOrder(PrintScope scope,String no);
    PrintFactRef resolveReference(PrintSourceReference reference,PrintScope scope);
    PrintFact snapshot(PrintFactRef ref);
    PrintExecutionGuard executionGuard(PrintFactRef ref);
    PrintFactPage scan(PrintFactCursor cursor,int limit);
}
