package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.OpsSpecTemplateVO;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.PlatformProductService;
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

import java.util.List;

/**
 * 平台端 · 规格模板维护（矩阵 P-3.4 / 待完成清单 E27）。
 *
 * <p><b>这块此前是断裂的</b>：B-4.4 商家建品时能选平台模板，而平台端没有任何维护入口 ——
 * 表里只有初始化时塞进去的几行，谁也改不了、加不了。三端联动表把它记成
 * 「❌ 断裂：模板是死的」。
 *
 * <p>模板归**类目维护面**（{@code product:category:*}）而不是商品审核面：
 * 模板是按品类预置的，与类目树、资质码字典是同一拨人在配。
 * 归审核码的话，只有审核员能维护模板，而审核员不碰类目结构。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSpecTemplateController {

    private final PlatformProductService platformProductService;
    private final AuditLogPort auditLogPort;
    private final ai.neargo.shop.product.service.SpecLibraryService specLibrary;

    public OpsSpecTemplateController(PlatformProductService platformProductService,
                                     AuditLogPort auditLogPort,
                                     ai.neargo.shop.product.service.SpecLibraryService specLibrary) {
        this.platformProductService = platformProductService;
        this.auditLogPort = auditLogPort;
        this.specLibrary = specLibrary;
    }

    /**
     * <b>类目 × 规格总览</b>（规格库 V195）：所有在售二级类目、各自支持哪些规格、每个规格有哪些取值。
     *
     * <p>为什么连<b>一条规格都没绑的类目也返回</b>：这张表真正要回答的是「哪些类目还没配」——
     * 只列已配的，缺口就永远看不见，而缺口的代价是那一类商家建品时只能手打，
     * 手打的选项没有 code，跨店聚合就此断掉。
     *
     * <p>不分页：29 个类目 × 各几个维度，一次拿全比翻页好用 —— 运营看这张表是为了横向比较。
     */
    @GetMapping("/ops/category-specs")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    public List<ai.neargo.shop.product.dto.CategorySpecVO> categorySpecs() {
        return specLibrary.catalog();
    }

    @GetMapping("/ops/spec-templates")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    public PageData<OpsSpecTemplateVO> list(@RequestParam(required = false) String categoryType,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "false") boolean showArchived,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "50") long size) {
        return platformProductService.listSpecTemplates(categoryType, keyword, showArchived, page, size);
    }

    /**
     * 新建或更新（{@code templateNo} 为空即新建）。
     *
     * <p>每个选项必须带 {@code code} —— 这是平台模板存在的唯一理由：
     * 自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」，带 code 才聚合得起来（B-4.5）。
     */
    @PostMapping("/ops/spec-templates")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    public OpsSpecTemplateVO save(@RequestBody SaveReq req) {
        var vo = platformProductService.saveSpecTemplate(new PlatformProductService.SaveTemplateCommand(
                req.templateNo(), req.categoryType(), req.name(),
                req.options() == null ? List.of()
                        : req.options().stream()
                                .map(o -> new MerchantGoodsService.SpecOption(o.code(), o.label()))
                                .toList()));
        /*
         * 记审计：模板改一次，**所有还没建品的商家跟着变** —— 选项少一个，
         * 那个规格从此没人能选；code 改一个，之前按老 code 建的商品就聚合不到一起了。
         */
        auditLogPort.record("SPEC_TEMPLATE_SAVE", vo.templateNo(),
                vo.name() + "（" + (vo.categoryType() == null ? "不限品类" : vo.categoryType())
                        + "，" + vo.options().size() + " 个选项）");
        return vo;
    }

    /** 归档：商家侧立刻不再下发。**不是删除** —— 历史商品还要靠 templateNo 解释它的 optionCode。 */
    @PostMapping("/ops/spec-templates/{templateNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    public OpsSpecTemplateVO archive(@PathVariable String templateNo) {
        var vo = platformProductService.archiveSpecTemplate(templateNo);
        auditLogPort.record("SPEC_TEMPLATE_ARCHIVE", templateNo, vo.name());
        return vo;
    }

    @PostMapping("/ops/spec-templates/{templateNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    public OpsSpecTemplateVO unarchive(@PathVariable String templateNo) {
        var vo = platformProductService.unarchiveSpecTemplate(templateNo);
        auditLogPort.record("SPEC_TEMPLATE_UNARCHIVE", templateNo, vo.name());
        return vo;
    }

    /**
     * @param templateNo   空 = 新建
     * @param categoryType 五品类之一；空 = 不限类目
     */
    public record SaveReq(String templateNo, String categoryType, String name, List<OptionReq> options) {
    }

    /** @param code 聚合键，**必填** */
    public record OptionReq(String code, String label) {
    }
}
