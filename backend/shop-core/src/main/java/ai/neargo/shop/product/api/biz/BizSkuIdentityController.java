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
     * <p><b>CSV 放在信封里回，不回裸文件。</b>试过两条更「正统」的路，都不通：
     *
     * <ul>
     *   <li>{@code ResponseEntity<byte[]>} —— 全局 {@code ApiResponseWrapper} 会把
     *       任何返回值包成 {@code ApiResult}（只放过 String），于是稳定回 500。
     *       全站此前一个 ResponseEntity 都没有，也就没人踩过这颗雷。</li>
     *   <li>裸 {@code String} + {@code text/csv} —— 服务端这侧通了，但**端上不认**：
     *       shared 的 http-client 见到不是 {@code {code,msg,data}} 的响应，
     *       直接判「响应格式不符合契约」。为一个导出改动全站的网络层不值当。</li>
     * </ul>
     *
     * <p>文件名也交给端上：它本来就要把这段文本转成 Blob 才能存盘，顺手起个名不多一步。
     * （而且设 Content-Disposition 要 {@code HttpServletResponse} 参数，
     * 架构守卫禁止 {@code product..} 碰 web 运行时。）
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/sku-identity/export")
    public ExportFile export() {
        return new ExportFile(service.exportCsv(BizContext.requireMerchantNo()));
    }

    /** 导出的正文。单独一个记录而不是裸字符串：将来要加「导出了几行」不必改形状 */
    public record ExportFile(String csv) {}

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
