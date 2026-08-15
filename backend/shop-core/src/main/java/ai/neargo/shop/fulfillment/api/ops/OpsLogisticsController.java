package ai.neargo.shop.fulfillment.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.fulfillment.dto.CarrierConfigVO;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;
import ai.neargo.shop.fulfillment.dto.ShipmentVO;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 物流（P-5.2）：运单、运费模板、运力档案。
 *
 * <p><b>一期只做快递 + 商家自送</b>（ADR-005 §5）：这里的运力档案是**配置存储**，
 * 不对接第三方即时配送 API；运单轨迹同理，先做运单号回填与记录，
 * 不接快递鸟/菜鸟。做成「存得下、看得见」而不假装它已经在跟对方系统通信。
 */
@Profile("ops")
@RestController
@Validated
public class OpsLogisticsController {

    private final LogisticsService logisticsService;
    private final AuditLogPort auditLogPort;

    public OpsLogisticsController(LogisticsService logisticsService, AuditLogPort auditLogPort) {
        this.logisticsService = logisticsService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/shipments")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_LOGISTICS_READ + "')")
    public PageData<ShipmentVO> shipments(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String carrier,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return PageData.ofAll(logisticsService.shipments(status, carrier, keyword), page, size);
    }

    /**
     * 换运单号。<b>必须写原因</b>：改运单号意味着「之前那个号是错的」，
     * 而用户可能已经拿着旧号在查件 —— 客服要能答出为什么变了。
     */
    @PostMapping("/ops/shipments/{shipmentNo}/waybill")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public ShipmentVO updateWaybill(@PathVariable String shipmentNo,
                                    @RequestBody WaybillReq req) {
        var vo = logisticsService.updateWaybill(shipmentNo, req.waybillNo(), req.reason(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("SHIPMENT_WAYBILL", shipmentNo,
                "改为 " + req.waybillNo() + "：" + req.reason());
        return vo;
    }

    @GetMapping("/ops/freight-templates")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_LOGISTICS_READ + "')")
    public PageData<FreightTemplateVO> freightTemplates(
            @RequestParam(defaultValue = "false") boolean showArchived,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return PageData.ofAll(logisticsService.freightTemplates(showArchived), page, size);
    }

    @PostMapping("/ops/freight-templates")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public FreightTemplateVO saveFreightTemplate(@RequestBody FreightTemplateReq req) {
        var vo = logisticsService.saveFreightTemplate(new LogisticsService.FreightTemplateCmd(
                req.templateNo(), req.name(),
                nz(req.firstWeightGram()), nzL(req.firstFee()),
                nz(req.addWeightGram()), nzL(req.addFee()),
                nzL(req.freeThreshold()), Boolean.TRUE.equals(req.isDefault()),
                req.outOfRange() == null ? List.of() : req.outOfRange()),
                SecurityUtils.currentUserNo());
        auditLogPort.record("FREIGHT_TEMPLATE", vo.templateNo(), vo.name());
        return vo;
    }

    @PostMapping("/ops/freight-templates/{templateNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public FreightTemplateVO archiveFreightTemplate(@PathVariable String templateNo) {
        var vo = logisticsService.archiveFreightTemplate(templateNo, SecurityUtils.currentUserNo());
        auditLogPort.record("FREIGHT_TEMPLATE_ARCHIVE", templateNo, "归档");
        return vo;
    }

    @PostMapping("/ops/freight-templates/{templateNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public FreightTemplateVO unarchiveFreightTemplate(@PathVariable String templateNo) {
        var vo = logisticsService.unarchiveFreightTemplate(templateNo, SecurityUtils.currentUserNo());
        auditLogPort.record("FREIGHT_TEMPLATE_ARCHIVE", templateNo, "取消归档");
        return vo;
    }

    @GetMapping("/ops/fulfillment/carriers")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_LOGISTICS_READ + "')")
    public List<CarrierConfigVO> carriers() {
        return logisticsService.carriers();
    }

    @PutMapping("/ops/fulfillment/carriers/{carrier}")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public CarrierConfigVO saveCarrier(@PathVariable String carrier,
                                       @RequestBody CarrierReq req) {
        var vo = logisticsService.saveCarrier(carrier, req.name(), nz(req.priority()),
                req.pickupCutoff(), nz(req.slaHours()), SecurityUtils.currentUserNo());
        auditLogPort.record("CARRIER_CONFIG", carrier, req.name());
        return vo;
    }

    /**
     * 启停运力。三条闸挡的都是<b>「订单发不出去」</b>而不是「显示不对」：
     * 没配密钥不能启用、还有在途单不能停用、不能停掉最后一家。
     */
    @PostMapping("/ops/fulfillment/carriers/{carrier}/enabled")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public CarrierConfigVO setCarrierEnabled(@PathVariable String carrier,
                                             @RequestBody EnabledReq req) {
        boolean on = Boolean.TRUE.equals(req.enabled());
        var vo = logisticsService.setCarrierEnabled(carrier, on, SecurityUtils.currentUserNo());
        // 停用一家运力 = 一批订单换路走，critical
        auditLogPort.record("CARRIER_ENABLED", carrier, on ? "启用" : "停用", true);
        return vo;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }

    /** @param reason 必填 —— 用户可能已拿着旧号在查件 */
    public record WaybillReq(String waybillNo, String reason) {
    }

    public record FreightTemplateReq(String templateNo, String name,
                                     Integer firstWeightGram, Long firstFee,
                                     Integer addWeightGram, Long addFee,
                                     Long freeThreshold, Boolean isDefault,
                                     List<FreightTemplateVO.OutOfRangeVO> outOfRange) {
    }

    public record CarrierReq(String name, Integer priority, String pickupCutoff, Integer slaHours) {
    }

    public record EnabledReq(Boolean enabled) {
    }
}
