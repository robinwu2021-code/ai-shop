package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Reverse BEAUTY_APPOINTMENT source callback; token credentials do not replace current source ownership. */
public interface BeautySourceInternalApi {
    String BASE = "/internal/beauty/appointment-source/";
    @PostExchange(BASE + "freeze") ResourceSourceAuthorityPort.Decision freeze(@RequestBody Delegation request);
    @PostExchange(BASE + "query") ResourceSourceAuthorityPort.Decision query(@RequestBody Delegation request);
    record Delegation(DelegatedIdentity identity, ResourceSourceAuthorityPort.Request request) { }
}
