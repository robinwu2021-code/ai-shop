package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/**
 * A missing original provider must never silently authorize a menu or activate a channel
 * connection.
 */
public interface ChannelCatalogAuthorityService {
    /** Older owners cannot silently resolve a new external identity contract. */
    default Facts resolve(IdentityQuery query) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    default Facts capture(Query query) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
