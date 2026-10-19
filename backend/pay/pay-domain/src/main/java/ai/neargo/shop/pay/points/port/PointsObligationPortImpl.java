package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.query.PointsObligationService;
import ai.neargo.shop.spi.points.PointsObligationPort;

import org.springframework.stereotype.Component;

@Component
public class PointsObligationPortImpl implements PointsObligationPort {
    private final PointsObligationService service;

    public PointsObligationPortImpl(PointsObligationService service) {
        this.service = service;
    }

    @Override
    public Obligations inspect(String store) {
        return service.inspect(store, null);
    }

    @Override
    public Obligations inspectSubOrder(String sub) {
        return service.inspect(null, sub);
    }
}
