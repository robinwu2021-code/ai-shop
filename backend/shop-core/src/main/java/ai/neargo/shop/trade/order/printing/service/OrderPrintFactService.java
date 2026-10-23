package ai.neargo.shop.trade.order.printing.service;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService.ConfirmedPayment;

import java.util.*;

public interface OrderPrintFactService {
    List<PrintFactRef> referencesByOrder(PrintScope scope, String orderNo);

    List<PrintFactRef> recordPaid(String orderNo, ConfirmedPayment confirmed);

    PrintFactRef resolveReference(PrintSourceReference reference, PrintScope scope);

    PrintFact snapshot(PrintFactRef ref);

    PrintFactPage scan(PrintFactCursor cursor, int limit);

    PrintExecutionGuard executionGuard(PrintFactRef ref);

    void recordControl(
            String orderNo,
            String subOrderNo,
            String controlType,
            String controlFactNo,
            java.time.Instant occurredAt);
}
