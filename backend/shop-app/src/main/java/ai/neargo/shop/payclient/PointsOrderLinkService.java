package ai.neargo.shop.payclient;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import org.springframework.stereotype.Service;

/** Source binding is immutable proof of the ledger holder's order association. */
@Service
public class PointsOrderLinkService {
    private final OrderPointsBindingService bindings;
    public PointsOrderLinkService(OrderPointsBindingService bindings) { this.bindings=bindings; }
    public String forUser(String sub, String user) {
        var binding=bindings.find(sub);
        return binding!=null && binding.admission().userNo().equals(user)?sub:null;
    }
    public String forStore(String sub, String store) {
        var binding=bindings.find(sub);
        return binding!=null && binding.admission().storeNo().equals(store)?sub:null;
    }
}
