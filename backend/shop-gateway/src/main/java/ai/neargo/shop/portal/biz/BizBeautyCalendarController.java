package ai.neargo.shop.portal.biz;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAppointmentSlot;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AppointmentSlotMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingCalendarResource;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceMapper;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only booking projection. Resource maintenance retains its existing permissions. */
@Profile("api")
@ConditionalOnProperty(name = "shop.beauty.enabled", havingValue = "true")
@RestController
public class BizBeautyCalendarController {
    private final StoreDirectoryPort stores;
    private final ResourceMapper resources;
    private final AppointmentSlotMapper slots;
    private final ScheduleWindowsPort schedules;

    public BizBeautyCalendarController(StoreDirectoryPort stores, ResourceMapper resources,
            AppointmentSlotMapper slots, ScheduleWindowsPort schedules) {
        this.stores = stores;
        this.resources = resources;
        this.slots = slots;
        this.schedules = schedules;
    }

    @GetMapping("/biz/x/beauty/calendar")
    @PreAuthorize("@perm.canBiz('" + BizPerms.BOOKING_VIEW + "') or @perm.canBiz('" + BizPerms.BOOKING_MANAGE + "')")
    public CalendarView calendar(@RequestParam Instant from, @RequestParam Instant to,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(1)) > 0
                || from.isBefore(Instant.parse("1000-01-03T00:00:00Z"))
                || to.isAfter(Instant.parse("9999-12-29T00:00:00Z"))) throw BizException.of(ErrorCode.BAD_REQUEST);
        var tenant = SecurityUtils.requireUser().tenantNo();
        var entity = BizContext.requireMerchantNo();
        var store = BizContext.requireStoreNo();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !card.active()) throw BizException.of(ErrorCode.NOT_FOUND);
        StoreRequestScopeValidator.requireStoreTarget(entity, store, card.entityNo(), card.storeNo(),
                requestedStore, BizContext.current().storeNos());

        var rows = DataScopeContext.executeWithoutScope(() -> resources.selectBookingCalendarResources(
                tenant, entity, store, LocalDateTime.ofInstant(from, ZoneOffset.UTC), LocalDateTime.ofInstant(to, ZoneOffset.UTC)));
        if (rows.size() > 200) throw BizException.of(ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
        var windows = new ArrayList<CalendarWindow>();
        for (int row = 0; row < rows.size(); row += 50) {
            var ids = new HashSet<String>();
            for (var resource : rows.subList(row, Math.min(row + 50, rows.size()))) ids.add(resource.resourceNo());
            for (var window : schedules.getBatch(new ScheduleWindowsPort.Query(tenant, entity, store, ids, from, to))) {
                windows.add(new CalendarWindow(window.resourceNo(), window.startAt(), window.endAt()));
            }
        }
        var closed = DataScopeContext.executeWithoutScope(() -> slots.selectList(Wrappers.<MchAppointmentSlot>lambdaQuery()
                .eq(MchAppointmentSlot::getTenantNo, tenant).eq(MchAppointmentSlot::getEntityNo, entity)
                .eq(MchAppointmentSlot::getStoreNo, store).eq(MchAppointmentSlot::getStatus, MchAppointmentSlot.CLOSED)
                .lt(MchAppointmentSlot::getStartAt, to.toEpochMilli()).gt(MchAppointmentSlot::getEndAt, from.toEpochMilli())
                .orderByAsc(MchAppointmentSlot::getStartAt).last("LIMIT 501")));
        if (closed.size() > 500) throw BizException.of(ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
        return new CalendarView(rows, windows, closed.stream()
                .map(slot -> new ClosedWindow(Instant.ofEpochMilli(slot.getStartAt()), Instant.ofEpochMilli(slot.getEndAt())))
                .toList());
    }

    public record CalendarView(List<BookingCalendarResource> resources, List<CalendarWindow> windows,
            List<ClosedWindow> closures) { }
    public record CalendarWindow(String resourceNo, Instant startAt, Instant endAt) { }
    public record ClosedWindow(Instant startAt, Instant endAt) { }
}
