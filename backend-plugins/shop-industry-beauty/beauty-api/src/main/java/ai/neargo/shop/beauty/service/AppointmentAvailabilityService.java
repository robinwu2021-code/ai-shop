package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.AvailabilityQuery;
import ai.neargo.shop.beauty.dto.AvailabilityVO;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;

/** Current authenticated scope is verified for every appointment operation. */
public interface AppointmentAvailabilityService {
    AvailabilityVO queryAvailability(String entityNo, String currentStoreNo, String actorNo, AvailabilityQuery query);
    PublicAvailabilityVO queryPublicAvailability(PublicAvailabilityQuery query);
}
