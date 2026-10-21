package ai.neargo.shop.beauty.client;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.AvailabilityQuery;
import ai.neargo.shop.beauty.dto.AvailabilityVO;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.common.PageData;

/** Same authenticated service contract across a process boundary, without retrying uncertain writes. */
public final class RemoteAppointmentAvailabilityService implements AppointmentAvailabilityService {
    private final BeautyBookingInternalApi api;
    public RemoteAppointmentAvailabilityService(BeautyBookingInternalApi api) { this.api = api; }
    @Override
    public AvailabilityVO queryAvailability(String entityNo, String currentStoreNo, String actorNo,
            AvailabilityQuery query) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.queryAvailability(new
                BeautyBookingInternalApi.QueryAvailability(entityNo, currentStoreNo, actorNo, query,
                DelegatedIdentityContext.current(actorNo)))));
    }
    @Override
    public PublicAvailabilityVO queryPublicAvailability(PublicAvailabilityQuery query) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.queryPublicAvailability(new
                BeautyBookingInternalApi.QueryPublicAvailability(query))));
    }
}
