package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogAuthorityInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;

/**
 * A single authenticated read preserves failures and never substitutes a locally invented grant.
 */
public final class RemoteChannelCatalogAuthorityService implements ChannelCatalogAuthorityService {
    private final ChannelCatalogAuthorityInternalApi api;
    private final Clock clock;

    public RemoteChannelCatalogAuthorityService(ChannelCatalogAuthorityInternalApi api) {
        this(api, Clock.systemUTC());
    }

    public RemoteChannelCatalogAuthorityService(
            ChannelCatalogAuthorityInternalApi api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Facts capture(Query query) {
        ChannelCatalogAuthorityPolicy.validateQuery(query);
        try {
            var facts = ServiceCalls.call(ServiceName.COMMERCE, () -> api.capture(query));
            ChannelCatalogAuthorityPolicy.validate(query, facts, clock.instant());
            return facts;
        } catch (ServiceCallException failure) {
            var code =
                    switch (failure.statusCode()) {
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
