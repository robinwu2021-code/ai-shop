package ai.neargo.shop.portal.ops;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.IsoTime;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort.Shipment;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营端「快递与轨迹」（TDD-物流模块 M10 / O1–O4）。从 shop-core 的 {@code OpsLogisticsController} 搬来，
 * 改调 {@link LogisticsAdminPort} —— 物流将来独立成服务，controller 留在主应用。
 *
 * <p><b>数据域在这里显式算</b>：物流不装数据域引擎，{@code lgs_waybill} 也没登记进去（登记了的话
 * 推送回调、outbox 消费这些没有会话的路径会被 fail-closed 成空白）。运营会话的商家范围
 * 由这里换成 {@code entityNos} 传给物流：不限 → null；只配了社区 / 自提点维度（运单上没有这两列）→ 空集，
 * 一条都看不到 —— 与数据域引擎对「表上缺这个锚点」的处理同口径。
 */
@Profile("ops")
@RestController
public class OpsShipmentController {

    private final LogisticsAdminPort logistics;
    private final AuditLogPort auditLogPort;

    public OpsShipmentController(LogisticsAdminPort logistics, AuditLogPort auditLogPort) {
        this.logistics = logistics;
        this.auditLogPort = auditLogPort;
    }

    /** O1。数据库分页（原来是查全量后在内存里分页） */
    @GetMapping("/ops/shipments")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_LOGISTICS_READ + "')")
    public PageData<ShipmentVO> shipments(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String carrier,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String subState,
                                          @RequestParam(required = false) String bindState,
                                          @RequestParam(required = false) String subChannel,
                                          @RequestParam(required = false) String profile,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        var p = logistics.list(new LogisticsAdminPort.ShipmentQuery(status, carrier, keyword, subState, bindState,
                subChannel, profile, entityScope(), page, size));
        return new PageData<>(p.records().stream().map(ShipmentVO::of).toList(), p.total(), page, size);
    }

    /** O2。原地换号，旧号的订阅与 token 作废，按新号重新订阅 */
    @PostMapping("/ops/shipments/{shipmentNo}/waybill")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public ShipmentVO updateWaybill(@PathVariable String shipmentNo, @RequestBody WaybillReq req) {
        var vo = ShipmentVO.of(logistics.changeWaybill(shipmentNo, req.carrier(), req.waybillNo(), req.reason(),
                entityScope()));
        auditLogPort.record("SHIPMENT_WAYBILL", shipmentNo, "改为 " + req.waybillNo() + "：" + req.reason());
        return vo;
    }

    /**
     * O3。只改状态、发事件，立即返回 —— 结果看列表（订阅状态 / 换 token 状态）。
     *
     * @return {@code {"accepted": true}}
     */
    @PostMapping("/ops/shipments/{shipmentNo}/replay")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_LOGISTICS_REPLAY + "')")
    public Map<String, Boolean> replay(@PathVariable String shipmentNo, @RequestBody ReplayReq req) {
        logistics.replay(shipmentNo, req.action(), req.channel(), entityScope());
        auditLogPort.record("SHIPMENT_REPLAY", shipmentNo,
                req.action() + (req.channel() == null || req.channel().isBlank() ? "" : " @" + req.channel()));
        return Map.of("accepted", true);
    }

    /** O4。不分页 */
    @GetMapping("/ops/logistics/channels")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_LOGISTICS_READ + "')")
    public List<LogisticsAdminPort.Channel> channels() {
        return logistics.channels();
    }

    /** 运营会话的商家范围：null = 不限；空集 = 看不到 */
    static Set<String> entityScope() {
        DataScopeSpec spec = SecurityUtils.currentUser().map(LoginUser::dataScope).orElse(null);
        if (spec == null || spec.all()) {
            return null;
        }
        Set<String> out = new HashSet<>();
        for (DataScopeSpec.Rule r : spec.rules()) {
            if (ScopeDim.MERCHANT.equals(r.dim()) && r.refs() != null) {
                out.addAll(r.refs());
            }
        }
        return out;
    }

    /** @param carrier 可空 = 承运商不变 */
    public record WaybillReq(String waybillNo, String reason, String carrier) {
    }

    /**
     * @param action  SUBSCRIBE / WX_BIND
     * @param channel 只对 SUBSCRIBE 有意义；空 = 重走路由链
     */
    public record ReplayReq(String action, String channel) {
    }

    /**
     * 运单（运营端）。时间沿用 ISO 字符串（这一端一直是这套，见 物流-API §1）。
     *
     * @param displayChannel    <b>弃用</b>，过渡期由 {@code bindState} 推导：换到 token 是 {@code wx-plugin}，否则 {@code self-map}
     * @param displayFailReason <b>弃用</b>，过渡期 = {@code bindError}
     */
    public record ShipmentVO(String shipmentNo, String orderNo, String carrier, String waybillNo, String status,
                             String receiver, String region, String createdAt, String updatedAt,
                             List<TraceVO> traces, String displayChannel, String displayFailReason,
                             String profile, String storeNo, String entityNo,
                             String subState, String subChannel, String subError, Integer subAttempts,
                             String bindState, String bindError, String signedAt, String lastEventAt,
                             boolean atLocker, String carrierCorrectedFrom, String receiverPhoneLast4) {

        static ShipmentVO of(Shipment s) {
            boolean plugin = "DONE".equals(s.bindState());
            return new ShipmentVO(s.shipmentNo(), s.orderNo(), s.carrier(), s.waybillNo(), s.status(),
                    s.receiver(), s.region(), IsoTime.toIso(s.createdAt()), IsoTime.toIso(s.updatedAt()),
                    s.traces().stream().map(n -> new TraceVO(IsoTime.toIso(n.at()), n.text(), n.location())).toList(),
                    plugin ? "wx-plugin" : "self-map", s.bindError(),
                    s.profile(), s.storeNo(), s.entityNo(),
                    s.subState(), s.subChannel(), s.subError(), s.subAttempts(),
                    s.bindState(), s.bindError(), IsoTime.toIso(s.signedAt()), IsoTime.toIso(s.lastEventAt()),
                    s.atLocker(), s.carrierCorrectedFrom(), s.receiverPhoneLast4());
        }
    }

    public record TraceVO(String at, String text, String location) {
    }
}
