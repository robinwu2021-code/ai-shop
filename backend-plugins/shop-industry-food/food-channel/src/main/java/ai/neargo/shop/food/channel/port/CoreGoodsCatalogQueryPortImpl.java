package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import java.util.function.Supplier;

/** Independent restaurant reads metadata from the original product authority, without fallback or retry. */
public class CoreGoodsCatalogQueryPortImpl implements GoodsCatalogQueryPort {
    private final Supplier<GoodsCatalogInternalApi> api;

    public CoreGoodsCatalogQueryPortImpl(Supplier<GoodsCatalogInternalApi> api) { this.api = api; }

    @Override
    public GoodsFact getGoods(String entityNo, String storeNo, String goodsNo) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var fact = api.get().lookup(new GoodsCatalogInternalApi.Lookup(entityNo, storeNo, goodsNo));
                if (fact == null || !Objects.equals(entityNo, fact.entityNo())
                        || !Objects.equals(storeNo, fact.storeNo()) || !Objects.equals(goodsNo, fact.goodsNo())) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid product metadata response");
                }
                return fact;
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}
