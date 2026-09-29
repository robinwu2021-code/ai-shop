package ai.neargo.shop.elec.api.b;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.SupplierDtos.BatchPreview;
import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.RemapReq;
import ai.neargo.shop.elec.dto.SupplierDtos.RenewResult;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.dto.SupplierDtos.SupplierView;
import ai.neargo.shop.elec.service.ElecStockImportService;
import ai.neargo.shop.elec.service.ElecSupplierService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 元器件 B 端 · 供应商。
 *
 * <p><b>B 端与 C 端是同一个账号</b>（小程序登录的那个号，C 端令牌）：在元器件页点「成为供应商」
 * 就成为供应商。「是不是供应商」由本服务查 elc_supplier_member，不进令牌 —— 供应商是元器件的概念，
 * 主系统不该知道。每个接口的 supplier_no 都由服务端从登录态推出来，不收端上传的。
 */
@ConditionalOnElec
@RestController
public class ElecSupplierController {

    private final ElecSupplierService suppliers;
    private final ElecStockImportService imports;

    public ElecSupplierController(ElecSupplierService suppliers, ElecStockImportService imports) {
        this.suppliers = suppliers;
        this.imports = imports;
    }

    /** 我的供应商档案；还没入驻时 data 为 null（不是 404 —— 端上据此显示「成为供应商」） */
    @GetMapping("/elec/b/supplier")
    public SupplierView mine() {
        return suppliers.mine(SecurityUtils.currentUserNo());
    }

    /** 成为供应商：<b>一点就成</b>，body 可空（公司名等之后在资料里补）。唯一前提是绑过手机号 */
    @PostMapping("/elec/b/supplier")
    public SupplierView register(@RequestBody(required = false) RegisterReq req) {
        return suppliers.register(SecurityUtils.currentUserNo(),
                req == null ? new RegisterReq(null, null, null, null, null) : req);
    }

    /** 补资料：公司名、类型、城市、联系人、联系电话 */
    @PutMapping("/elec/b/supplier")
    public SupplierView update(@RequestBody RegisterReq req) {
        return suppliers.update(SecurityUtils.currentUserNo(), req);
    }

    /** @param filter ALL / EXPIRING / EXPIRED */
    @GetMapping("/elec/b/stock")
    public List<StockView> stocks(@RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(defaultValue = "ALL") String filter,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return suppliers.stocks(SecurityUtils.currentUserNo(), keyword, filter, page, size);
    }

    /** 「仍有货」：在售库存全部续期 */
    @PostMapping("/elec/b/stock/renew")
    public RenewResult renew() {
        return suppliers.renew(SecurityUtils.currentUserNo());
    }

    /**
     * 上传库存表（.xlsx / .csv）。返回预览，<b>这一步一行库存都不动</b>。
     *
     * @param mode        MERGE 只改表里有的 / REPLACE 表里没有的下架
     * @param taxIncluded 价格含不含税；不传按表头猜（写了「未税」按未税），都没写按含税
     */
    @PostMapping("/elec/b/stock/upload")
    public BatchPreview upload(@RequestParam(value = "file", required = false) MultipartFile file,
                               @RequestParam(defaultValue = "MERGE") String mode,
                               @RequestParam(required = false) Boolean taxIncluded) {
        // 先认人再看文件：file 设成可选，是为了让「没登录」永远先于「没带文件」被说出来 ——
        // 必填的话参数解析在方法之前就 400 了，匿名探测看不出它要不要登录（MpEndpointAuthTest）
        String userNo = SecurityUtils.currentUserNo();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        return imports.upload(userNo, file.getOriginalFilename(), bytes, mode, taxIncluded);
    }

    @PostMapping("/elec/b/stock/batch/{batchNo}/remap")
    public BatchPreview remap(@PathVariable String batchNo, @RequestBody RemapReq req) {
        return imports.remap(SecurityUtils.currentUserNo(), batchNo, req.columns());
    }

    @PostMapping("/elec/b/stock/batch/{batchNo}/apply")
    public BatchPreview apply(@PathVariable String batchNo) {
        return imports.apply(SecurityUtils.currentUserNo(), batchNo);
    }
}
