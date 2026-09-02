package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.service.StoreQrcodeService;
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

/**
 * 平台端 · 店铺码档案与印刷量登记（P-10.1.3）。
 *
 * <p>此前 {@code /ops/stores/qrcodes} 整条是 mock：页面有数、点得动、不报错，
 * 后端一行都没有。它卡住的**不是接口而是指标源** ——
 * 扫码次数要埋点（V290 补上了），印刷量是线下事实（本批改成运营录入）。
 *
 * <p><b>没登记过印刷量的店，{@code printed} 给 null 不给 0</b>：
 * 「还没人登记」与「印了 0 张」是两件事，混成一个数之后运营没法知道该去催谁。
 *
 * <p>权限用 {@code store:page:audit}：后端没有 {@code store:qrcode:export} 这个码
 * （它只是 ops-web 的 ui 码，在 UI_PERM_MAP 里是 UNIMPLEMENTED）。
 * 审店招的与发店铺码的是同一拨人（BD），复用它就不必新增权限码、
 * 也就不必动 ROLE_PERMS 与权限种子 —— 与获客看板同一处理。
 */
@Profile("ops")
@RestController
@Validated
public class OpsStoreQrcodeController {

    /** 扫码次数的缺省统计窗口，与获客看板一致（30 天）。 */
    private static final long DEFAULT_WINDOW_MS = 30L * 24 * 3600 * 1000;

    private final StoreQrcodeService qrcodeService;
    private final AuditLogPort auditLogPort;

    public OpsStoreQrcodeController(StoreQrcodeService qrcodeService, AuditLogPort auditLogPort) {
        this.qrcodeService = qrcodeService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 店铺码列表：码 + 区间内扫码次数 + 累计印量。
     *
     * @param from 扫码统计区间起（毫秒）；不传取 {@code to - 30 天}
     */
    @GetMapping("/ops/stores/qrcodes")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public PageData<StoreQrcodeService.QrcodeRow> qrcodes(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        long end = to == null ? System.currentTimeMillis() : to;
        long start = from == null ? end - DEFAULT_WINDOW_MS : from;
        return qrcodeService.list(keyword, start, end, page, Math.min(size, 100));
    }

    /**
     * 登记一次印刷。<b>留痕</b> —— 这是一笔会进成本对账的数，
     * 事后要能回答「这 500 张是谁登记的」。
     */
    @PostMapping("/ops/stores/{merchantNo}/qrcode/print")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public void recordPrint(@PathVariable String merchantNo, @RequestBody PrintReq req) {
        int qty = req.qty() == null ? 0 : req.qty();
        qrcodeService.recordPrint(merchantNo, qty, req.size(), req.remark(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("STORE_QRCODE_PRINT", merchantNo,
                qty + " 张" + (req.size() == null ? "" : "｜" + req.size())
                        + (req.remark() == null ? "" : "｜" + req.remark()));
    }

    /**
     * @param qty <b>有符号</b>：印多了冲减传负数，补一行而不是改历史行。0 会被拒
     */
    public record PrintReq(Integer qty, String size, String remark) {
    }
}
