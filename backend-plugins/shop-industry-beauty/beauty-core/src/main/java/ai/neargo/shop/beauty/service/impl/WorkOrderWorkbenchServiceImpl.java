package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.dto.WorkOrderSummaryVO;
import ai.neargo.shop.beauty.mapper.WorkOrderWorkbenchMapper;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Reads persisted roots only after current live store access; no client employee impersonation. */
public final class WorkOrderWorkbenchServiceImpl implements WorkOrderWorkbenchService {
    private final AppointmentAccess access;
    private final AppointmentPersistence db;
    private final WorkOrderWorkbenchMapper rows;

    public WorkOrderWorkbenchServiceImpl(
            AppointmentAccess access, AppointmentPersistence db, WorkOrderWorkbenchMapper rows) {
        this.access = access;
        this.db = db;
        this.rows = rows;
    }

    @Override
    public PageData<WorkOrderSummaryVO> list(
            String entity, String store, String actor, WorkOrderQueryReq query) {
        access.merchant(entity, store, actor, false);
        if (query.storeNo() != null && !store.equals(query.storeNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var from = utc(query.from());
        var to = utc(query.to());
        return db.read(
                () -> {
                    long total = rows.count(entity, store, query, from, to);
                    var records =
                            rows
                                    .list(
                                            entity,
                                            store,
                                            query,
                                            from,
                                            to,
                                            query.size(),
                                            (query.page() - 1L) * query.size())
                                    .stream()
                                    .map(
                                            r ->
                                                    new WorkOrderSummaryVO(
                                                            r.getEntityNo(),
                                                            r.getStoreNo(),
                                                            r.getWorkOrderNo(),
                                                            r.getAppointmentNo(),
                                                            r.getStatus(),
                                                            r.getScopeRevision(),
                                                            r.getVersion(),
                                                            offset(r.getCreatedAt()),
                                                            offset(r.getStartedAt()),
                                                            offset(r.getFinishedAt()),
                                                            offset(r.getClosedAt())))
                                    .toList();
                    return new PageData<>(records, total, query.page(), query.size());
                });
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
