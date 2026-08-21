package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.SpuStdVO;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.SpuStdService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台标准品库（TDD-标准品库）。
 *
 * <p><b>标准品不属于任何一家商家</b>，所以这几条不接数据域 —— 与类目同类。
 * 按商家维度过滤一份跨商家共享的主数据，只会让所有人都搜不到。
 */
/*
 * ⚠️ **必须是 "ops" 不是 "api"** —— S8 部署隔离：一个 jar 三种起法，
 * /ops/** 与 /mp,/biz 的控制器各标各的 profile，两者互斥。
 * 标错的症状是**这几条端点在运营端实例上根本不注册**，请求返回 404，
 * 而单测里看不出来（测试上下文两个 profile 都在）。
 */
@Profile("ops")
@RestController
public class OpsSpuStdController {

    private final SpuStdService service;

    public OpsSpuStdController(SpuStdService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_STD_READ + "')")
    @GetMapping("/ops/spu-std")
    public PageData<SpuStdVO> list(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String categoryNo,
                                   @RequestParam(defaultValue = "false") boolean showArchived,
                                   @RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long size) {
        return service.list(keyword, categoryNo, showArchived, page, Math.min(size, 50));
    }

    /**
     * 新建 / 更新。<b>每个规格选项必须带 code</b> —— 这是标准品存在的唯一理由：
     * 没有 code，三家店的「500g」「五百克」「0.5kg」永远聚合不到一起。
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_STD_UPDATE + "')")
    @PostMapping("/ops/spu-std")
    public SpuStdVO save(@RequestBody SaveReq req) {
        return service.save(new SpuStdService.SaveCommand(
                req.stdNo(), req.categoryNo(), req.title(), req.titleI18n(), req.subtitle(),
                req.cover(), req.images(),
                req.specGroups() == null ? List.of() : req.specGroups().stream()
                        .map(g -> new MerchantGoodsService.SpecGroup(
                                g.name(), g.options(), g.optionCodes(), g.templateNo()))
                        .toList(),
                req.keywords()));
    }

    /**
     * 归档。<b>不检查有没有商品在引用</b>（与类目归档相反）——
     * {@code std_no} 是溯源不是外键：归档只是「以后别再从这条建品」，
     * 已经建出来的商品照常在售。拦住反而会让一条录错的标准品因为被引用过就永远撤不下来。
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_STD_UPDATE + "')")
    @PostMapping("/ops/spu-std/{stdNo}/archive")
    public SpuStdVO archive(@PathVariable String stdNo) {
        return service.archive(stdNo);
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_STD_UPDATE + "')")
    @PostMapping("/ops/spu-std/{stdNo}/unarchive")
    public SpuStdVO unarchive(@PathVariable String stdNo) {
        return service.unarchive(stdNo);
    }

    public record SaveReq(String stdNo, String categoryNo, String title,
                          Map<String, String> titleI18n, String subtitle,
                          String cover, List<String> images,
                          List<SpecGroupReq> specGroups, String keywords) {
    }

    public record SpecGroupReq(String name, List<String> options, List<String> optionCodes,
                               String templateNo) {
    }
}
