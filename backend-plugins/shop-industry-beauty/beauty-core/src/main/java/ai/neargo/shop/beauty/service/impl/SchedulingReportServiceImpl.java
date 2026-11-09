package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.SchedulingReportQuery;
import ai.neargo.shop.beauty.dto.SchedulingReportVO;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.SchedulingReportService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** BAPI-119 首期子集：只读计数，判权与预约列表相同（{@link AppointmentAccess#merchant} 读）。 */
public class SchedulingReportServiceImpl implements SchedulingReportService {
    private static final Duration MAX_WINDOW = Duration.ofDays(93);
    private final BeautyMappers.AppointmentMapper appointments;
    private final AppointmentAccess access;

    public SchedulingReportServiceImpl(BeautyMappers.AppointmentMapper appointments, AppointmentAccess access) {
        this.appointments = appointments;
        this.access = access;
    }

    @Override
    public SchedulingReportVO scheduling(String entity, String store, String actor, SchedulingReportQuery q) {
        if (q == null || q.from() == null || q.to() == null || !q.to().isAfter(q.from())
                || Duration.between(q.from(), q.to()).compareTo(MAX_WINDOW) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        access.merchant(entity, store, actor, false);
        LocalDateTime from = q.from().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = q.to().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return DataScopeContext.executeWithoutScope(() -> {
            long booked = 0;
            long arrived = 0;
            long noShow = 0;
            long cancelled = 0;
            for (Map<String, Object> row : appointments.reportStatusCounts(entity, store, from, to)) {
                String status = (String) value(row, "status");
                long n = count(row);
                switch (status) {
                    case "HELD" -> { continue; }
                    case "CHECKED_IN" -> arrived += n;
                    case "NO_SHOW" -> noShow += n;
                    case "CANCELLED" -> cancelled += n;
                    default -> { }
                }
                booked += n;
            }
            long expected = booked - cancelled;
            Integer rate = expected <= 0 ? null : (int) (arrived * 10_000 / expected);
            List<SchedulingReportVO.PersonnelCount> people = appointments
                    .reportPersonnelCounts(entity, store, from, to).stream()
                    .map(r -> new SchedulingReportVO.PersonnelCount((String) value(r, "personnel_no"), count(r)))
                    .toList();
            List<SchedulingReportVO.ServiceCount> services = appointments
                    .reportTopServices(entity, store, from, to).stream()
                    .map(r -> new SchedulingReportVO.ServiceCount((String) value(r, "sku_no"), count(r)))
                    .toList();
            return new SchedulingReportVO(q.from(), q.to(), booked, arrived, rate, noShow, cancelled, people,
                    services);
        });
    }

    /** 列别名大小写随驱动；按不区分大小写取 */
    private static Object value(Map<String, Object> row, String key) {
        for (var e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        throw new IllegalStateException("report column missing: " + key);
    }

    private static long count(Map<String, Object> row) {
        return ((Number) value(row, "n")).longValue();
    }
}
