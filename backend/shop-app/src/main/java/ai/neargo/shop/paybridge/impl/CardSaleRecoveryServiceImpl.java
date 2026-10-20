package ai.neargo.shop.paybridge.impl;
import ai.neargo.shop.paybridge.CardSaleRecoveryService;
import ai.neargo.shop.paybridge.CardSaleCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.PaidCardSaleRecoveryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class CardSaleRecoveryServiceImpl implements CardSaleRecoveryService {
    private final PaidCardSaleRecoveryPort source;
    private final CardSaleCoordinator coordinator;
    public CardSaleRecoveryServiceImpl(PaidCardSaleRecoveryPort source,CardSaleCoordinator coordinator) {
        this.source=source; this.coordinator=coordinator;
    }
    @Override public Result recover(EntitlementContext ctx,String after,int limit) {
        var orders=source.pending(ctx,after,limit); int completed=0,failed=0;
        for(String no:orders) {
            try {
                var result=coordinator.recover(ctx,no);
                if(result.total()>0 && (result.issued()==result.total() || result.released()==result.total())) {
                    source.acknowledge(ctx,no); completed++;
                } else failed++;
            } catch(RuntimeException failure) { failed++; }
        }
        return new Result(orders.size(),completed,failed,orders.isEmpty()?"":orders.getLast());
    }
}
