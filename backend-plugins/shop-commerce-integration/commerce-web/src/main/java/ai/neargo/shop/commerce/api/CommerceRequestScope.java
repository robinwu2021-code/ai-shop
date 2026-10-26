package ai.neargo.shop.commerce.api;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

/** 首批账号及连接管理仅主体属主；门店同时核对上下文、请求头和原门店目录。 */
public final class CommerceRequestScope {
    private final StoreDirectoryPort stores;

    public CommerceRequestScope(StoreDirectoryPort stores) {
        this.stores = stores;
    }

    public String owner() {
        String entity = BizContext.requireMerchantNo();
        if (!BizContext.current().owner()) throw BizException.of(ErrorCode.FORBIDDEN);
        return entity;
    }

    public String store(String target, HttpServletRequest request) {
        String entity = owner();
        var context = BizContext.current();
        String header = request.getHeader(BizContextFilter.STORE_HEADER);
        if (target == null
                || !target.equals(context.currentStoreNo())
                || !context.storeNos().contains(target)
                || header != null && !header.equals(target)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var card = stores.cards(List.of(target)).get(target);
        if (card == null || !entity.equals(card.entityNo()) || !target.equals(card.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return entity;
    }

    public static void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream()
                        .anyMatch(values -> values.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
