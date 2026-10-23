package ai.neargo.shop.food.api;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/** Same trusted platform context and directory ownership guard as table configuration. */
public final class FoodBizStoreScope {
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator validator;
    public FoodBizStoreScope(StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.stores=stores;this.validator=validator;
    }
    public BizContext current(HttpServletRequest request) { return path(BizContext.current().currentStoreNo(),request); }
    public BizContext path(String store,HttpServletRequest request) {
        var context=BizContext.current();
        validator.requireStoreTarget(context.merchantNo(),context.currentStoreNo(),context.merchantNo(),store,
                request.getHeader(BizContextFilter.STORE_HEADER),context.storeNos());
        var card=stores.cards(List.of(store)).get(store);
        if(card==null) throw BizException.of(ErrorCode.NOT_FOUND);
        validator.requireStoreTarget(context.merchantNo(),context.currentStoreNo(),card.entityNo(),card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER),context.storeNos());
        return context;
    }
}
