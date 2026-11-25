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
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Bounded nonbinding candidate search; previews with durable identities are created by the authenticated service. */
public final class AppointmentAvailabilityServiceImpl implements AppointmentAvailabilityService {
    private final AppointmentMatching matching;
    private final AppointmentAccess access;
    private final AppointmentPersistence db;
    private final AppointmentCoordinator coordinator;

    public AppointmentAvailabilityServiceImpl(AppointmentMatching matching, AppointmentAccess access,
            AppointmentPersistence db, AppointmentCoordinator coordinator) {
        this.matching = matching; this.access = access;
        this.db = db; this.coordinator = coordinator;
    }
    @Override
    public AvailabilityVO queryAvailability(String entity, String store, String actor, AvailabilityQuery q) {
        access.merchant(entity, store, actor, q.replaceAppointmentNo() != null);
        AppointmentPersistence.require(store.equals(q.storeNo()) && entity.equals(matching.scope(store).entityNo()));
        ResourceCandidatesPort.Replacement replacement = null;
        if (q.replaceAppointmentNo() != null) {
            var root = db.root(q.replaceAppointmentNo());
            if (!entity.equals(root.getEntityNo()) || !store.equals(root.getStoreNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            access.root(root, true);
            AppointmentPersistence.require("CONFIRMED".equals(root.getStatus()) && db.current(root) != null);
            replacement = coordinator.replacement(root);
        }
        return search(q, false, replacement);
    }
    @Override
    public PublicAvailabilityVO queryPublicAvailability(PublicAvailabilityQuery q) {
        var result = search(new AvailabilityQuery(q.storeNo(), q.items(), q.from(), q.to(), null), true, null);
        return new PublicAvailabilityVO(result.candidates(), result.expiresAt(), result.complete());
    }
    private AvailabilityVO search(AvailabilityQuery q, boolean publicOnly, ResourceCandidatesPort.Replacement replacement) {
        if (q.from() == null || q.to() == null || !q.to().isAfter(q.from())
                || Duration.between(q.from(), q.to()).compareTo(Duration.ofDays(1)) > 0
                || q.from().getNano() != 0 || Math.floorMod(q.from().toEpochSecond(), 300) != 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return matching.withAdvisoryInspection(() -> searchWindow(q, publicOnly, replacement));
    }

    private AvailabilityVO searchWindow(AvailabilityQuery q, boolean publicOnly, ResourceCandidatesPort.Replacement replacement) {
        var scope = matching.scope(q.storeNo());
        int step = q.slotIntervalMinutes() == null ? 5 : q.slotIntervalMinutes();
        Instant latest = Instant.now().plus(matching.bookingAheadDays(q.storeNo()), ChronoUnit.DAYS);
        List<AppointmentPreviewVO> result = new ArrayList<>();
        OffsetDateTime at = q.from();
        boolean complete = true;
        int checked = 0;
        for (; at.isBefore(q.to()) && !at.toInstant().isAfter(latest) && checked < 48; at = at.plusMinutes(step)) {
            if (!at.toInstant().isAfter(Instant.now())) continue;
            checked++;
            try {
                var match = matching.match(scope, publicOnly, q.items(), List.of(), q.requestedPersonnelNo(), at, replacement);
                complete &= match.complete();
                result.add(new AppointmentPreviewVO(null, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1),
                        match.policyVersionNo(), match.startAt(), match.endAt(), match.snapshot().items(),
                        match.snapshot().assignments(), match.snapshot().priceMinor()));
            } catch (BizException unavailable) {
                if (unavailable.errorCode() == ErrorCode.BEAUTY_SEARCH_INCOMPLETE) { complete = false; }
                else if (unavailable.errorCode() != ErrorCode.CONFLICT
                        && !(unavailable.errorCode() == ErrorCode.BAD_REQUEST && !at.toInstant().isAfter(Instant.now()))) {
                    throw unavailable;
                }
            }
        }
        return new AvailabilityVO(result, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1), complete &&
                (!at.isBefore(q.to()) || at.toInstant().isAfter(latest)));
    }
}
