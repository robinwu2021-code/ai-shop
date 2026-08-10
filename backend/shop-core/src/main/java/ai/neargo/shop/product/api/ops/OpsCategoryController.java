package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.product.dto.OpsCategoryVO;
import ai.neargo.shop.product.service.CategoryService;
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
 * 平台端 · 类目树维护。
 *
 * <p>ops-web 早就声明了这四个端点并写好了页面与 mock，后端一直缺 ——
 * 于是运营端的类目页在真实环境里是四个 404，而 mock 上一切正常。
 *
 * <p><b>类目与 {@code prd_goods.type} 是两个正交维度</b>：type 决定履约与合规
 * （冷链 / 不发货 / iOS 可售规则），是平台硬编码的；类目决定归类与准入，运营可维护。
 * 详见 {@code docs/technical/类目树补齐方案.md}。
 */
@Profile("ops")
@RestController
@Validated
public class OpsCategoryController {

    private final CategoryService categoryService;
    private final AuditLogPort auditLogPort;

    public OpsCategoryController(CategoryService categoryService, AuditLogPort auditLogPort) {
        this.categoryService = categoryService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/categories")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public ai.neargo.shop.common.PageData<OpsCategoryVO> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String template,
                                     @RequestParam(defaultValue = "false") boolean showArchived,
                                     @RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(categoryService.list(keyword, template, showArchived), page, size);
    }

    /** 新建或更新（{@code categoryNo} 为空即新建）。 */
    @PostMapping("/ops/categories")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public OpsCategoryVO save(@RequestBody SaveReq req) {
        var vo = categoryService.save(new CategoryService.SaveCategoryCommand(
                req.categoryNo(), req.name(), req.i18nEn(), req.parentNo(), req.template(),
                req.qualifications(), req.requiredCode(), req.icon(), req.sort()));
        /*
         * 记审计：类目上挂着 required_code —— 改它等于放宽或收紧一整类商品的准入门槛。
         * 这类改动出了事要能查到是谁在什么时候动的。
         */
        auditLogPort.record("CATEGORY_SAVE", vo.categoryNo(),
                vo.name() + (vo.requiredCode() == null ? "（无资质门槛）" : "，资质码 " + vo.requiredCode()));
        return vo;
    }

    @PostMapping("/ops/categories/{categoryNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public OpsCategoryVO archive(@PathVariable String categoryNo) {
        var vo = categoryService.archive(categoryNo);
        auditLogPort.record("CATEGORY_ARCHIVE", categoryNo, vo.name());
        return vo;
    }

    @PostMapping("/ops/categories/{categoryNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public OpsCategoryVO unarchive(@PathVariable String categoryNo) {
        var vo = categoryService.unarchive(categoryNo);
        auditLogPort.record("CATEGORY_UNARCHIVE", categoryNo, vo.name());
        return vo;
    }

    /**
     * @param categoryNo 空 = 新建
     * @param i18nEn     英文名。与 ops-web 的表单字段同名
     */
    public record SaveReq(String categoryNo, String name, String i18nEn, String parentNo,
                          String template, List<String> qualifications, String requiredCode,
                          String icon, Integer sort) {
    }
}
