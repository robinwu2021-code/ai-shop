package ai.neargo.shop.printbridge.port;
import ai.neargo.shop.trade.order.printing.BusinessPrintFactService;
import org.springframework.stereotype.Component;
import java.util.Set;
@Component
public class RechargePrintSourceFactsPortImpl extends BusinessPrintSourceFactsPortImpl {
    public RechargePrintSourceFactsPortImpl(BusinessPrintFactService facts) { super(facts); }
    public Set<String> scenes() { return Set.of("RECHARGE_RECEIPT"); }
    public Set<String> sourceTypes() { return Set.of("RECHARGE"); }
}
