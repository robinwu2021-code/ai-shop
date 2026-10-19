package ai.neargo.shop.payclient.port;

import ai.neargo.shop.spi.points.*;

import org.springframework.stereotype.Component;

/** Pay recovery scans the original source journal through its SPI, never mutable order tables. */
@Component
public class OrderPointsSourcePortAdapter implements SourceFactsPort {
    private final OrderPointsSourcePort source;

    public OrderPointsSourcePortAdapter(OrderPointsSourcePort source) {
        this.source = source;
    }

    @Override
    public Page scan(String type, long after, int limit) {
        return source.scan(type, after, limit);
    }
}
