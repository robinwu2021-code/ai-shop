package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.product.service.SkuIdentityService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品编码（条码 / 货号 / 单位）的批量导入导出（P4）。
 *
 * <p><b>单开一个控制器</b>：{@code BizGoodsController} 已经装着 11 个资源，
 * 是内聚守卫榜上的第一名。往上再加两条只会让那张榜更难看，
 * 而这三列本来就有自己的服务（{@link SkuIdentityService}）。
 *
 * <p>权限用 {@code biz:goods} —— 它改的是商品数据。不另立权限码：
 * 能改一件商品编码的人，与能批量改的人是同一批，多一个码只会多一处配错的机会。
 */
@Profile("api")
@RestController
public class BizSkuIdentityController {

    private final SkuIdentityService service;

    public BizSkuIdentityController(SkuIdentityService service) {
        this.service = service;
    }

    /**
     * 导出本店全部规格行的身份三列。
     *
     * <p><b>返回类型必须是 String。</b>全局的 {@code ApiResponseWrapper} 会把任何返回值
     * 包成 {@code ApiResult}，只放过 {@code String}（它自己的注释里写着为什么：
     * 包成对象再交给 StringHttpMessageConverter 会 ClassCastException）。
     * 我第一版返回 {@code ResponseEntity<byte[]>}，于是这个接口稳定地回 500 ——
     * 而全站此前一个 ResponseEntity 都没有，也就没人踩过这颗雷。
     *
     * <p><b>不设 Content-Disposition。</b>那要一个 {@code HttpServletResponse} 参数，
     * 而这个类在 {@code ai.neargo.shop.product..} 下 —— 架构守卫禁止领域包碰 web 运行时
     * （理由写在规则里：读了 request 就只能在 HTTP 线程里跑）。文件名由端上决定，
     * 反正它本来就要把文本转成 Blob 才能存盘，顺手起个名不多一步。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping(value = "/biz/sku-identity/export", produces = "text/csv;charset=UTF-8")
    public String export() {
        return service.exportCsv(BizContext.requireMerchantNo());
    }

    /** 试算：这份表会改什么、哪几行有问题。<b>不写库</b> */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/sku-identity/import/plan")
    public SkuIdentityService.ImportReport plan(@RequestBody CsvReq req) {
        return service.plan(BizContext.requireMerchantNo(), req.csv());
    }

    /** 真写。只写没问题的那些行，问题照旧报出来 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/sku-identity/import")
    public SkuIdentityService.ImportReport apply(@RequestBody CsvReq req) {
        return service.apply(BizContext.requireMerchantNo(), req.csv());
    }

    /**
     * 整份文件用 JSON 字符串传，不用 multipart。
     *
     * <p>端上要先读文件才能显示试算结果，读都读了就没必要再让服务端解一遍 multipart；
     * 而且小程序端没有 file input，只能走「粘贴」这条路 —— 两端同一个接口。
     */
    public record CsvReq(String csv) {}
}
