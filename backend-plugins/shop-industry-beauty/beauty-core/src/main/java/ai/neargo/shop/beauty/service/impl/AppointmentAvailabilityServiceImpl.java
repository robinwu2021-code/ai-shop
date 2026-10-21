package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.AvailabilityQuery;
import ai.neargo.shop.beauty.dto.AvailabilityVO;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Bounded nonbinding candidate search; previews with durable identities are created by the authenticated service. */
public final class AppointmentAvailabilityServiceImpl implements AppointmentAvailabilityService {
    private final AppointmentMatching matching;
    private final AppointmentAccess access;
    public AppointmentAvailabilityServiceImpl(AppointmentMatching matching, AppointmentAccess access) {
        this.matching = matching; this.access = access;
    }
    @Override
    public AvailabilityVO queryAvailability(String entity, String store, String actor, AvailabilityQuery q) {
        access.merchant(entity, store, actor, false);
        AppointmentPersistence.require(store.equals(q.storeNo()) && entity.equals(matching.scope(store).entityNo()));
        return search(q, false);
    }
    @Override
    public PublicAvailabilityVO queryPublicAvailability(PublicAvailabilityQuery q) {
        var result = search(new AvailabilityQuery(q.storeNo(), q.items(), q.from(), q.to(), null), true);
        return new PublicAvailabilityVO(result.candidates(), result.expiresAt(), result.complete());
    }
    private AvailabilityVO search(AvailabilityQuery q, boolean publicOnly) {
        if (q.from() == null || q.to() == null || !q.to().isAfter(q.from())
                || Duration.between(q.from(), q.to()).compareTo(Duration.ofDays(1)) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var scope = matching.scope(q.storeNo());
        List<AppointmentPreviewVO> result = new ArrayList<>();
        OffsetDateTime at = q.from();
        boolean complete = true;
        int checked = 0;
        for (; at.isBefore(q.to()) && checked < 48; at = at.plusMinutes(5), checked++) {
            try {
                var match = matching.match(scope, publicOnly, q.items(), List.of(), q.requestedPersonnelNo(), at);
                complete &= match.complete();
                result.add(new AppointmentPreviewVO(null, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1),
                        match.policyVersionNo(), match.startAt(), match.endAt(), match.snapshot().items(),
                        match.snapshot().assignments(), match.snapshot().priceMinor()));
            } catch (BizException unavailable) {
                if (unavailable.errorCode() == ErrorCode.BEAUTY_SEARCH_INCOMPLETE) { complete = false; }
                else if (unavailable.errorCode() != ErrorCode.CONFLICT) { throw unavailable; }
            }
        }
        return new AvailabilityVO(result, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1), complete &&
                !at.isBefore(q.to()));
    }
}
