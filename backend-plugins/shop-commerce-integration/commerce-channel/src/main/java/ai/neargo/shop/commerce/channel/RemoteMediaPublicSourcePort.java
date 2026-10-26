package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourceInternalApi;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Facts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;

/**
 * A single authenticated read preserves failures and never substitutes an invented image address.
 */
public final class RemoteMediaPublicSourcePort implements MediaPublicSourcePort {
    private final MediaPublicSourceInternalApi api;
    private final Clock clock;

    public RemoteMediaPublicSourcePort(MediaPublicSourceInternalApi api) {
        this(api, Clock.systemUTC());
    }

    public RemoteMediaPublicSourcePort(MediaPublicSourceInternalApi api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Facts capture(Request query) {
        MediaPublicSourceFacts.validateRequest(query);
        try {
            var facts = ServiceCalls.call(ServiceName.PLATFORM, () -> api.capture(query));
            MediaPublicSourceFacts.validate(query, facts, clock.instant());
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
