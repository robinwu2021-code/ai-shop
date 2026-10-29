package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Consumer;

/**
 * 预留（{@code RESERVATION} 能力包，TDD §2.3「下单预留跨进程」，M8 T8.1）。
 *
 * <p>单号是调用方的（商城的订单号）；业主取自凭证。<b>预留全成全败、同单号同内容幂等</b>，
 * {@code ttlSeconds} 到期没确认由服务自己回收 —— 跨进程之后调用方可能永远不回来。
 * 动作与商城 {@code InventoryReservationPort} 一一对应：预留 → 先货后款受理 / 付款分配 → 出库 → 取消。
 */
@RestController
public class ReservationController {

    private final ReservationService reservations;
    private final BusinessProfileService profiles;
    private final CommandService commands;

    public ReservationController(ReservationService reservations, BusinessProfileService profiles,
                                 CommandService commands) {
        this.reservations = reservations;
        this.profiles = profiles;
        this.commands = commands;
    }

    @PostMapping("/api/inventory/v1/reservations")
    public View reserve(@RequestBody Reserve req) {
        Caller caller = enabled();
        if (req.ref() == null || req.lines() == null || req.lines().isEmpty()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "reservation");
        }
        List<ReservationService.Line> lines = req.lines().stream()
                .map(l -> new ReservationService.Line(l.itemId(), l.locationId(), l.qty(), l.fulfillmentRef()))
                .toList();
        return commands.execute(caller, "reservation.reserve", req, View.class, () -> {
            reservations.reserve(caller.ownerId(), req.ref(), lines, req.ttlSeconds(),
                    req.lifecycleVersion() == null ? 1 : req.lifecycleVersion());
            return view(caller.ownerId(), req.ref());
        });
    }

    @GetMapping("/api/inventory/v1/reservations/{ref}")
    public View query(@PathVariable String ref) {
        Caller caller = enabled();
        return view(caller.ownerId(), ref);
    }

    /** 先货后款：受理但不记付款，受理后的预留不再到期回收。 */
    @PostMapping("/api/inventory/v1/reservations/{ref}/accept-unpaid")
    public View acceptUnpaid(@PathVariable String ref) {
        return act("reservation.accept_unpaid", ref, owner -> reservations.acceptUnpaid(owner, ref));
    }

    /** 付款成功：预留转为已分配，不再到期回收。可重试。 */
    @PostMapping("/api/inventory/v1/reservations/{ref}/allocate-paid")
    public View allocatePaid(@PathVariable String ref) {
        return act("reservation.allocate_paid", ref, owner -> reservations.allocatePaid(owner, ref));
    }

    /** 交付：按履约单出库（自动生成销售出库单）。 */
    @PostMapping("/api/inventory/v1/reservations/{ref}/issue")
    public View issue(@PathVariable String ref, @RequestBody Issue req) {
        Caller caller = enabled();
        return commands.execute(caller, "reservation.issue", new InboundController.Keyed(ref, req), View.class,
                () -> {
                    if (reservations.issue(caller.ownerId(), ref, req.fulfillmentRef(),
                            caller.actor() == null ? caller.systemCode() : caller.actor()) == null) {
                        throw InventoryException.of(InventoryError.CONFLICT, "reservation.not_issuable");
                    }
                    return view(caller.ownerId(), ref);
                });
    }

    /** 取消：未出库的部分全部释放；预留还没到也会留下墓碑，迟到的预留不再占货。 */
    @PostMapping("/api/inventory/v1/reservations/{ref}/cancel")
    public View cancel(@PathVariable String ref) {
        return act("reservation.cancel", ref, owner -> reservations.cancelByRef(owner, ref));
    }

    private View act(String type, String ref, Consumer<String> action) {
        Caller caller = enabled();
        return commands.execute(caller, type, ref, View.class, () -> {
            action.accept(caller.ownerId());
            return view(caller.ownerId(), ref);
        });
    }

    private View view(String owner, String ref) {
        ReservationService.Snapshot s = reservations.query(owner, ref)
                .orElseThrow(() -> InventoryException.of(InventoryError.NOT_FOUND, "reservation"));
        return new View(ref, s.reservationId(), s.status().name(), s.lifecycleVersion(), s.paymentAcknowledged(),
                s.lines().stream().map(l -> new Line(l.itemId(), l.locationId(), l.qty(), l.fulfillmentRef()))
                        .toList());
    }

    private Caller enabled() {
        Caller caller = InventoryRequestContext.caller();
        profiles.requireCapability(caller.ownerId(), Capability.RESERVATION);
        return caller;
    }

    /** @param lifecycleVersion 缺省 1；与商城订单的库存生命周期版本一致 */
    public record Reserve(String ref, List<Line> lines, long ttlSeconds, Integer lifecycleVersion) {
    }

    public record Line(String itemId, String locationId, int qty, String fulfillmentRef) {
    }

    public record Issue(String fulfillmentRef) {
    }

    public record View(String ref, String reservationId, String status, int lifecycleVersion,
                       boolean paymentAcknowledged, List<Line> lines) {
    }
}
