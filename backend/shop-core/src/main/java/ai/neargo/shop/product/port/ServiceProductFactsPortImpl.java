package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.mapper.ProductMappers.ServiceDefinitionMapper;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves ownership in product SQL and delegates price/shelf overlays to the existing product quote owner.
 *
 * <p>时长：商品有服务定义时取这个 SKU 现在适用的那一行（覆盖行或默认行），否则取商品自己的时长
 * （零售到店核销等没有服务定义的服务）。
 */
@Component
public class ServiceProductFactsPortImpl implements ServiceProductFactsPort {
    private final ServiceGoods services;
    private final ServiceDefinitionMapper definitions;
    private final GoodsQueryPort quotes;

    public ServiceProductFactsPortImpl(ServiceGoods services, ServiceDefinitionMapper definitions,
            GoodsQueryPort quotes) {
        this.services = services;
        this.definitions = definitions;
        this.quotes = quotes;
    }

    @Override
    public Fact get(String entityNo, String storeNo, String skuNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var found = services.resolve(entityNo, storeNo, skuNo, false);
            var product = found.goods();
            var definition = definitions.current(product.getGoodsNo(), skuNo);
            Integer duration = definition != null ? definition.getDurationMin() : product.getDurationMin();
            if (duration == null || duration < 1) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            var quote = quotes.snapshot(List.of(skuNo), Map.of(entityNo, storeNo)).get(skuNo);
            if (quote == null || !quote.onSale()) { throw BizException.of(ErrorCode.NOT_FOUND); }
            if (!entityNo.equals(quote.merchantNo()) || !storeNo.equals(quote.storeNo())
                    || !product.getGoodsNo().equals(quote.goodsNo()) || !skuNo.equals(quote.skuNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return new Fact(entityNo, storeNo, skuNo, product.getGoodsNo(), quote.price(), duration,
                    product.getVersion(), found.sku().getVersion());
        });
    }
}
