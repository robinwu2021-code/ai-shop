package ai.neargo.shop.printbridge.port;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.trade.order.printing.service.OrderPrintFactService;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class OrderPrintSourceFactsPortImpl implements PrintSourceFactsPort {
    private final OrderPrintFactService facts;

    public OrderPrintSourceFactsPortImpl(OrderPrintFactService f) {
        facts = f;
    }

    public List<PrintFactRef> referencesByOrder(PrintScope s, String no) {
        return facts.referencesByOrder(s, no);
    }

    public Set<String> scenes() {
        return Set.of("ORDER_PAID");
    }

    public Set<String> sourceTypes() {
        return Set.of("SUB_ORDER");
    }

    public PrintFactRef resolveReference(PrintSourceReference r, PrintScope s) {
        return facts.resolveReference(r, s);
    }

    public PrintFact snapshot(PrintFactRef r) {
        return facts.snapshot(r);
    }

    public PrintFactPage scan(PrintFactCursor c, int limit) {
        return facts.scan(c, limit);
    }

    public PrintExecutionGuard executionGuard(PrintFactRef r) {
        return facts.executionGuard(r);
    }
}
