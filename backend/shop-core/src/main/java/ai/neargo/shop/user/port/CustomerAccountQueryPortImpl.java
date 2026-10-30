package ai.neargo.shop.user.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;

import org.springframework.stereotype.Component;

import java.util.List;

/** Account existence comes from the original user owner after actual store ownership is checked. */
@Component
public final class CustomerAccountQueryPortImpl implements CustomerAccountQueryPort {
    private final StoreCardQueryPort stores;
    private final UserQueryPort users;

    public CustomerAccountQueryPortImpl(StoreCardQueryPort stores, UserQueryPort users) {
        this.stores = stores;
        this.users = users;
    }

    @Override
    public Result find(Query query) {
        if (query == null
                || !MaterialCatalogFacts.validKey(query.entityNo(), 32)
                || !MaterialCatalogFacts.validKey(query.storeNo(), 32)
                || !MaterialCatalogFacts.validKey(query.customerNo(), 32))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var card = stores.cards(List.of(query.storeNo())).get(query.storeNo());
        if (card == null
                || !query.entityNo().equals(card.entityNo())
                || !query.storeNo().equals(card.storeNo()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        var actual = users.find(query.customerNo());
        if (actual.isPresent() && !query.customerNo().equals(actual.get().userNo()))
            throw BizException.of(ErrorCode.CONFLICT);
        return new Result(query, actual.isPresent());
    }
}
