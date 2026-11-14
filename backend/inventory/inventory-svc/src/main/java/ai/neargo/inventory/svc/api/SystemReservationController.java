package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统级预留动作（M8，商城传统下单路径）：宿主手里只有订单号 / 履约单号、没有业主时用 ——
 * 一张父订单可能跨几个商家，释放、确认按订单号把这些商家的预留一起处理。
 *
 * <p>只认系统凭证（入口守卫保证），动作只碰<b>本系统、写入方为 SVC</b> 的业主；同一单号在内嵌那一侧的商家
 * 由宿主内嵌自己处理（同样只碰写入方为 EMBEDDED 的业主）。领域动作按单号幂等，不再经命令表。
 */
@RestController
public class SystemReservationController {

    private final ReservationService reservations;

    public SystemReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/api/inventory/v1/system/reservations/{ref}/release")
    public Map<String, String> release(@PathVariable String ref) {
        reservations.releaseByRef(caller().systemCode(), ref);
        return Map.of("ref", ref);
    }

    /** 付款确认（v1 生命周期）：预留转实扣并生成销售出库单；返回第一张出库单号，没有为空 */
    @PostMapping("/api/inventory/v1/system/reservations/{ref}/commit")
    public Map<String, String> commit(@PathVariable String ref) {
        Caller caller = caller();
        Map<String, String> out = new HashMap<>();
        out.put("ref", ref);
        out.put("outboundNo", reservations.commitByRef(caller.systemCode(), ref, operator(caller)));
        return out;
    }

    @PostMapping("/api/inventory/v1/system/reservations/{ref}/allocate-paid")
    public Map<String, String> allocatePaid(@PathVariable String ref) {
        reservations.allocatePaidByRef(caller().systemCode(), ref);
        return Map.of("ref", ref);
    }

    @PostMapping("/api/inventory/v1/system/fulfillments/{fulfillmentRef}/issue")
    public Map<String, String> issue(@PathVariable String fulfillmentRef) {
        Caller caller = caller();
        Map<String, String> out = new HashMap<>();
        out.put("fulfillmentRef", fulfillmentRef);
        out.put("outboundNo", reservations.issueByFulfillmentRef(caller.systemCode(), fulfillmentRef,
                operator(caller)));
        return out;
    }

    @PostMapping("/api/inventory/v1/system/fulfillments/{fulfillmentRef}/release-allocated")
    public Map<String, String> releaseAllocated(@PathVariable String fulfillmentRef) {
        reservations.releaseAllocatedByFulfillmentRef(caller().systemCode(), fulfillmentRef);
        return Map.of("fulfillmentRef", fulfillmentRef);
    }

    private static Caller caller() {
        return InventoryRequestContext.caller();
    }

    private static String operator(Caller caller) {
        return caller.actor() == null ? caller.systemCode() : caller.actor();
    }
}
