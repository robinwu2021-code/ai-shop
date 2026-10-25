package ai.neargo.shop.printbridge.port;
import ai.neargo.shop.trade.order.printing.BusinessPrintFactService;
import org.springframework.stereotype.Component;
import java.util.Set;
@Component
public class RefundPrintSourceFactsPortImpl extends BusinessPrintSourceFactsPortImpl {
    public RefundPrintSourceFactsPortImpl(BusinessPrintFactService facts) { super(facts); }
    public Set<String> scenes() { return Set.of("REFUND_RECEIPT"); }
    public Set<String> sourceTypes() { return Set.of("REFUND"); }
}
