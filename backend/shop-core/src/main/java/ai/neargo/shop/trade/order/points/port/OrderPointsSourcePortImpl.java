package ai.neargo.shop.trade.order.points.port;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.OrderPointsFactService;
import org.springframework.stereotype.Component;
import java.util.Optional;

/** Only source-owned read capabilities cross the business boundary. */
@Component
public class OrderPointsSourcePortImpl implements OrderPointsSourcePort {
    private final OrderPointsFactService service;
    public OrderPointsSourcePortImpl(OrderPointsFactService service){this.service=service;}
    @Override public Optional<PointsOrderEvent> find(String event){return service.find(event);}
    @Override public SourceFactsPort.Page scan(String type,long after,int limit){return service.scan(type,after,limit);}
    @Override public boolean settled(String store){return service.settled(store);}
    @Override public boolean settledSubOrder(String sub){return service.settledSubOrder(sub);}
}
