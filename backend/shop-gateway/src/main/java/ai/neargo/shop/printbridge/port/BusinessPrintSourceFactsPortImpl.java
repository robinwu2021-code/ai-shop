package ai.neargo.shop.printbridge.port;
import ai.neargo.shop.spi.printing.PrintSourceFactsPort;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.trade.order.printing.BusinessPrintFactService;
import java.util.*;
public class BusinessPrintSourceFactsPortImpl implements PrintSourceFactsPort {
    private final BusinessPrintFactService facts;
    public BusinessPrintSourceFactsPortImpl(BusinessPrintFactService facts) { this.facts=facts; }
    public Set<String> scenes() { return Set.of("REFUND_RECEIPT","RECHARGE_RECEIPT"); }
    public Set<String> sourceTypes() { return Set.of("REFUND","RECHARGE"); }
    public List<PrintFactRef> referencesByOrder(PrintScope s,String no) { return facts.referencesByOrder(s,no); }
    public PrintFactRef resolveReference(PrintSourceReference r,PrintScope s) { return facts.resolveReference(r,s); }
    public PrintFact snapshot(PrintFactRef r) { return facts.snapshot(r); }
    public PrintFactPage scan(PrintFactCursor c,int limit) { return facts.scan(c,limit); }
    public PrintExecutionGuard executionGuard(PrintFactRef r) { return facts.executionGuard(r); }
}
