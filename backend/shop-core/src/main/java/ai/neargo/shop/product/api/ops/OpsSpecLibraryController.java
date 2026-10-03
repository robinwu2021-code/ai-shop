package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.product.dto.CategorySpecVO;
import ai.neargo.shop.product.service.SpecLibraryService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 平台端 · 规格库（V195 四层模型的维护面）。
 *
 * <p><b>与类目分成两件事。</b>类目回答「卖什么」，规格库回答「有哪些规格」，
 * 类目 × 规格回答「谁用哪些」—— 此前三件事挤在「规格模板维护」一个页面里，
 * 而那张模板表已经退化成兜底。
 *
 * <p>权限走新开的 {@code product:spec:*} 而不是复用类目权限：类目权限还兼着资质门槛
 * （{@code required_code} 决定一整类商品的准入），而规格库改一条<b>会影响所有商家的建品页</b>。
 * 两件事的授权范围不该绑在一起。
 *
 * <p>每一个写操作都记审计：改一个值的文案，所有还没建品的商家跟着变；
 * 改一个 code，之前按老 code 建的商品就聚合不到一起了 —— 而后者不会有任何报错。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSpecLibraryController {

    private final SpecLibraryService specLibrary;
    private final AuditLogPort auditLogPort;

    public OpsSpecLibraryController(SpecLibraryService specLibrary, AuditLogPort auditLogPort) {
        this.specLibrary = specLibrary;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 规格项列表。
     *
     * @param universal {@code true} 只看通用、{@code false} 只看专用、不传 = 全部。
     *                  <b>运营端按它分成两个页面</b>：通用维度改一条全站生效，
     *                  专用维度只影响一个类目 —— 混在一张表里，改的人不知道自己动了多大范围
     */
    @GetMapping("/ops/spec-dims")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_READ + "')")
    public List<SpecLibraryService.SpecDimVO> dims(
            @RequestParam(required = false) Boolean universal,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean showArchived) {
        return specLibrary.listDims(universal, keyword, showArchived);
    }

    @PostMapping("/ops/spec-dims")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecDimVO saveDim(@RequestBody SaveDimReq req) {
        var vo = specLibrary.saveDim(new SpecLibraryService.SaveDimCommand(
                req.dimNo(), req.code(), req.name(), req.valueType(), req.unit(),
                req.usageType(), Boolean.TRUE.equals(req.universal()), req.sort()));
        auditLogPort.record("SPEC_DIM_SAVE", vo.dimNo(),
                vo.name() + "（" + (vo.universal() ? "通用" : "专用") + "，" + vo.usageType() + "）");
        return vo;
    }

    /** 归档：商家侧立刻不再下发。<b>不是删除</b> —— 历史商品还要靠它解释自己的 code */
    @PostMapping("/ops/spec-dims/{dimNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecDimVO archiveDim(@PathVariable String dimNo) {
        var vo = specLibrary.archiveDim(dimNo, true);
        auditLogPort.record("SPEC_DIM_ARCHIVE", dimNo, vo.name());
        return vo;
    }

    @PostMapping("/ops/spec-dims/{dimNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecDimVO unarchiveDim(@PathVariable String dimNo) {
        var vo = specLibrary.archiveDim(dimNo, false);
        auditLogPort.record("SPEC_DIM_UNARCHIVE", dimNo, vo.name());
        return vo;
    }

    @PostMapping("/ops/spec-values")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecValueVO saveValue(@RequestBody SaveValueReq req) {
        var vo = specLibrary.saveValue(new SpecLibraryService.SaveValueCommand(
                req.valueNo(), req.dimNo(), req.code(), req.label(),
                req.numericValue(), req.numericUnit(), req.aliases(), req.sort()));
        auditLogPort.record("SPEC_VALUE_SAVE", vo.valueNo(), vo.label());
        return vo;
    }

    @PostMapping("/ops/spec-values/{valueNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecValueVO archiveValue(@PathVariable String valueNo) {
        var vo = specLibrary.archiveValue(valueNo, true);
        auditLogPort.record("SPEC_VALUE_ARCHIVE", valueNo, vo.label());
        return vo;
    }

    @PostMapping("/ops/spec-values/{valueNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecValueVO unarchiveValue(@PathVariable String valueNo) {
        var vo = specLibrary.archiveValue(valueNo, false);
        auditLogPort.record("SPEC_VALUE_UNARCHIVE", valueNo, vo.label());
        return vo;
    }

    /**
     * 商家自建值 → 平台值。<b>编号不变</b>，所以已经按它建好的商品不用重建。
     * 用得多的自有值就该进公共值池 —— 但这是运营的判断，不自动发生。
     */
    @PostMapping("/ops/spec-values/{valueNo}/promote")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public SpecLibraryService.SpecValueVO promote(@PathVariable String valueNo) {
        var vo = specLibrary.promoteValue(valueNo);
        auditLogPort.record("SPEC_VALUE_PROMOTE", valueNo, vo.label() + " → 平台值");
        return vo;
    }

    /**
     * 合并重复值。**不是删除** —— 被合并的那几条退役并指向保留值，
     * 历史商品的 SKU 快照会被一并改写，否则那批货与保留值那一堆在聚合时各算各的。
     *
     * @return 改写了多少条 SKU 快照
     */
    @PostMapping("/ops/spec-values/merge")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public MergeResult merge(@RequestBody MergeReq req) {
        int rewritten = specLibrary.mergeValues(req.intoValueNo(), req.fromValueNos());
        auditLogPort.record("SPEC_VALUE_MERGE", req.intoValueNo(),
                "合并 " + (req.fromValueNos() == null ? 0 : req.fromValueNos().size())
                        + " 条，改写 " + rewritten + " 个 SKU 快照");
        return new MergeResult(rewritten);
    }

    public record MergeReq(String intoValueNo, List<String> fromValueNos) {
    }

    public record MergeResult(int rewrittenSkus) {
    }

    /** 类目 × 规格：所有在售二级类目与它支持的规格（<b>没配的也返回</b>，那正是要看的） */
    @GetMapping("/ops/category-specs")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_READ + "')")
    public List<CategorySpecVO> categorySpecs() {
        return specLibrary.catalog();
    }

    /**
     * 整份替换一个类目的规格绑定。
     *
     * <p>整份而不是逐条增删：绑定是一组<b>有序</b>的东西（顺序即 sort，主维度只能有一个），
     * 逐条 diff 出增删改没有收益，反而多一堆中间态。
     */
    @PostMapping("/ops/category-specs/{categoryNo}")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SPEC_UPDATE + "')")
    public List<CategorySpecVO> saveBindings(@PathVariable String categoryNo,
                                             @RequestBody List<BindingReq> body) {
        specLibrary.saveCategoryBindings(categoryNo,
                body == null ? List.of() : body.stream()
                        .map(b -> new SpecLibraryService.BindingCommand(b.dimNo(), b.usageType(),
                                Boolean.TRUE.equals(b.primary()), Boolean.TRUE.equals(b.required()),
                                b.valueNos(), b.labels()))
                        .toList());
        auditLogPort.record("CATEGORY_SPEC_SAVE", categoryNo,
                "绑定 " + (body == null ? 0 : body.size()) + " 个维度");
        return specLibrary.catalog();
    }

    /** @param dimNo 空 = 新建。code 一旦定下就不该改 —— 改码等于换一根聚合轴 */
    public record SaveDimReq(String dimNo, String code, String name, String valueType, String unit,
                             String usageType, Boolean universal, Integer sort) {
    }

    public record SaveValueReq(String valueNo, String dimNo, String code, String label,
                               BigDecimal numericValue, String numericUnit,
                               List<String> aliases, Integer sort) {
    }

    /** @param labels {@code valueNo → 类目内换名}：500g 在蔬菜下叫「约1斤」 */
    public record BindingReq(String dimNo, String usageType, Boolean primary, Boolean required,
                             List<String> valueNos, Map<String, String> labels) {
    }
}
