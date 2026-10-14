package ai.neargo.shop.fulfillment.api.ops;

import ai.neargo.shop.fulfillment.service.FreightDraftService;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.fulfillment.dto.CarrierConfigVO;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
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
import java.util.Map;

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
    private final FreightDraftService freightDraftService;
    private final LogisticsAdminPort logisticsAdmin;

    public OpsLogisticsController(LogisticsService logisticsService, AuditLogPort auditLogPort,
                                  FreightDraftService freightDraftService, LogisticsAdminPort logisticsAdmin) {
        this.logisticsService = logisticsService;
        this.auditLogPort = auditLogPort;
        this.freightDraftService = freightDraftService;
        this.logisticsAdmin = logisticsAdmin;
    }

    // 运单（/ops/shipments、换单号、重放、渠道总览）已搬到主应用 portal/ops/OpsShipmentController，
    // 改调 LogisticsAdminPort（TDD-物流模块 批 5）。这里只剩运费模板与承运商。

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

    /**
     * 从快递100 报价生成模板草稿（TDD-快递100商家寄件 §8 AC17）。**不保存** —— 运营在页面上核对、改过，
     * 再走上面的保存。权限与保存同一档：生成会打 62 次快递100 查价。
     */
    @PostMapping("/ops/freight-templates/draft")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public FreightDraftService.Draft draftFreightTemplate(@RequestBody FreightDraftReq req) {
        return freightDraftService.draft(req.origin(), req.carrier(),
                req.firstWeightGram() == null ? 1000 : req.firstWeightGram(),
                req.addWeightGram() == null ? 1000 : req.addWeightGram());
    }

    /** @param origin 发货地址（至少到城市）；@param carrier 微信 delivery_id；重量单位克，缺省 1000 / 1000 */
    public record FreightDraftReq(String origin, String carrier, Integer firstWeightGram, Integer addWeightGram) {
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
        Map<String, Map<String, String>> codes = logisticsAdmin.carrierCodes();
        return logisticsService.carriers().stream()
                .map(c -> c.withCodes(codes.getOrDefault(c.carrier(), Map.of()))).toList();
    }

    @PutMapping("/ops/fulfillment/carriers/{carrier}")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public CarrierConfigVO saveCarrier(@PathVariable String carrier,
                                       @RequestBody CarrierReq req) {
        var vo = logisticsService.saveCarrier(carrier, req.name(), nz(req.priority()),
                req.pickupCutoff(), nz(req.slaHours()), SecurityUtils.currentUserNo());
        // 不传 / 传空 = 不改编码（空 ≠ 清空）：老的页面只发前四个字段，不能因此把编码表清掉
        if (req.codes() != null && !req.codes().isEmpty()) {
            logisticsAdmin.saveCarrierCodes(carrier, req.codes());
        }
        auditLogPort.record("CARRIER_CONFIG", carrier, req.name()
                + (req.codes() == null || req.codes().isEmpty() ? "" : " 编码 " + req.codes()));
        return vo.withCodes(logisticsAdmin.carrierCodes().getOrDefault(carrier, Map.of()));
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
        return vo.withCodes(logisticsAdmin.carrierCodes().getOrDefault(carrier, Map.of()));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }

    public record FreightTemplateReq(String templateNo, String name,
                                     Integer firstWeightGram, Long firstFee,
                                     Integer addWeightGram, Long addFee,
                                     Long freeThreshold, Boolean isDefault,
                                     List<FreightTemplateVO.OutOfRangeVO> outOfRange) {
    }

    /** @param codes 各物流渠道里的编码 {@code {"kuaidi100":"shentong"}}；不传 = 不改 */
    public record CarrierReq(String name, Integer priority, String pickupCutoff, Integer slaHours,
                             Map<String, String> codes) {
    }

    public record EnabledReq(Boolean enabled) {
    }
}
