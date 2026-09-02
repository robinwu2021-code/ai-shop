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

    /** 导出封顶。每行要取一次码图（有额度），不封顶的话一次误操作就能把额度打穿。 */
    private static final long EXPORT_LIMIT = 500;

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
            @RequestParam(required = false) Boolean codeless,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        long end = to == null ? System.currentTimeMillis() : to;
        long start = from == null ? end - DEFAULT_WINDOW_MS : from;
        return qrcodeService.list(keyword, start, end, Boolean.TRUE.equals(codeless),
                page, Math.min(size, 100));
    }

    /**
     * 发码。<b>幂等</b>：已经有码就把原码给回来，重复点不会把码换掉 ——
     * 换码要走 {@code /reissue}，那条会让已印物料失效，不能靠点两次「发码」误触发。
     */
    @PostMapping("/ops/stores/{merchantNo}/qrcode/issue")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public IssuedVO issue(@PathVariable String merchantNo,
                          @RequestParam(required = false) String storeNo) {
        String operator = SecurityUtils.currentUserNo();
        String code = qrcodeService.issue(merchantNo, storeNo, operator);
        auditLogPort.record("STORE_QRCODE_ISSUE", merchantNo + (storeNo == null ? "" : ":" + storeNo),
                "发码 " + code);
        return new IssuedVO(code);
    }

    /**
     * <b>换码：旧码当场失效。</b>已经贴在店里的物料会全部变成死链 —— 必须给理由。
     *
     * <p>留痕写的是新旧两个码：事后追「这张贴纸为什么扫不出来」时，
     * 光有「换过码」这句话没用，要能对上手里那张纸上的码。
     */
    @PostMapping("/ops/stores/{merchantNo}/qrcode/reissue")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public IssuedVO reissue(@PathVariable String merchantNo, @RequestBody ReissueReq req) {
        String operator = SecurityUtils.currentUserNo();
        String code = qrcodeService.reissue(merchantNo, req.storeNo(), req.reason(), operator);
        auditLogPort.record("STORE_QRCODE_REISSUE",
                merchantNo + (req.storeNo() == null ? "" : ":" + req.storeNo()),
                "换码 → " + code + "｜原因：" + req.reason());
        return new IssuedVO(code);
    }

    /**
     * 导出：列表那几列 + <b>可直接印的码图</b>。
     *
     * <p>此前导出只有五列文本，拿到手不能直接印 —— 而「导出」这个动作的用途
     * 就是把物料交给印刷。取码图会消耗微信永久码额度，所以只在这条路上取，
     * 且封顶 {@value #EXPORT_LIMIT} 行。
     */
    @GetMapping("/ops/stores/qrcodes/export")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public java.util.List<StoreQrcodeService.ExportRow> export(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean codeless) {
        long end = to == null ? System.currentTimeMillis() : to;
        long start = from == null ? end - DEFAULT_WINDOW_MS : from;
        var rows = qrcodeService.exportRows(keyword, start, end,
                Boolean.TRUE.equals(codeless), EXPORT_LIMIT);
        auditLogPort.record("STORE_QRCODE_EXPORT", keyword == null ? "全部" : keyword,
                rows.size() + " 行");
        return rows;
    }

    /**
     * 登记一次印刷。<b>留痕</b> —— 这是一笔会进成本对账的数，
     * 事后要能回答「这 500 张是谁登记的」。
     */
    @PostMapping("/ops/stores/{merchantNo}/qrcode/print")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public void recordPrint(@PathVariable String merchantNo, @RequestBody PrintReq req) {
        int qty = req.qty() == null ? 0 : req.qty();
        qrcodeService.recordPrint(merchantNo, req.storeNo(), qty, req.size(), req.remark(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("STORE_QRCODE_PRINT",
                merchantNo + (req.storeNo() == null ? "" : ":" + req.storeNo()),
                qty + " 张" + (req.size() == null ? "" : "｜" + req.size())
                        + (req.remark() == null ? "" : "｜" + req.remark()));
    }

    /**
     * @param storeNo 印的是哪家店的码；<b>空 = 该主体的默认店</b>（单店商家不必先查门店号）
     * @param qty     <b>有符号</b>：印多了冲减传负数，补一行而不是改历史行。0 会被拒
     */
    public record PrintReq(String storeNo, Integer qty, String size, String remark) {
    }

    /**
     * @param storeNo 换哪家店的码；空 = 默认店
     * @param reason  <b>必填</b>。换码会让已印物料失效，线下代价与「点一下」完全不匹配，
     *                不留理由的话事后没人说得清那批贴纸是为什么废的
     */
    public record ReissueReq(String storeNo, String reason) {
    }

    /** @param storeCode 这家店现在的码 */
    public record IssuedVO(String storeCode) {
    }
}
