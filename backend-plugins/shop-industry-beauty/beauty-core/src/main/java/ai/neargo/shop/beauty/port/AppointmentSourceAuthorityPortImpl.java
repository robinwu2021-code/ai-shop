package ai.neargo.shop.beauty.port;

import ai.neargo.shop.beauty.service.impl.AppointmentSourceAuthority;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;

/** Focused source provider avoids eagerly wiring the booking executor back through the public appointment facade. */
public final class AppointmentSourceAuthorityPortImpl implements ResourceSourceAuthorityPort {
    private final AppointmentSourceAuthority authority;
    public AppointmentSourceAuthorityPortImpl(AppointmentSourceAuthority authority) { this.authority = authority; }
    @Override public String sourceType() { return "BEAUTY_APPOINTMENT"; }
    @Override public Decision freeze(Request request) { return authority.decide(request, true); }
    @Override public Decision query(Request request) { return authority.decide(request, false); }
}
