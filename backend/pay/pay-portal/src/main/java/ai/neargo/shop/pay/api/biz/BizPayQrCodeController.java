package ai.neargo.shop.pay.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.checkout.service.QrCodeService;
import ai.neargo.shop.pay.checkout.service.QrCodeService.CreateQrCmd;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本店静态收款码（F45）：生成（打印贴在店里 / 桌上）、停用 */
@Profile("api")
@RestController
@RequestMapping("/biz/pay/qr-code")
public class BizPayQrCodeController {

    private final QrCodeService codes;
    private final BizPayContext ctx;
    private final String checkoutBase;

    public BizPayQrCodeController(QrCodeService codes, BizPayContext ctx,
                                  @Value("${shop.pay.checkout.base-url:}") String checkoutBase) {
        this.codes = codes;
        this.ctx = ctx;
        this.checkoutBase = checkoutBase;
    }

    record CreateReq(String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
    }

    /** @param url 顾客扫的链接（前端据此出二维码） */
    record QrView(String qrCode, String kind, String tableRef, String currency, long singleLimitMinor,
                  long dailyLimitMinor, String status, String url) {
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_QR + "')")
    @GetMapping
    public List<QrView> list() {
        Long storeId = ctx.storeId();
        return codes.ofStore(ctx.caller(storeId), storeId).stream().map(this::view).toList();
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_QR + "')")
    @PostMapping
    public QrView create(@RequestBody CreateReq req) {
        Long storeId = ctx.storeId();
        return view(codes.create(ctx.caller(storeId), storeId, new CreateQrCmd(req.kind(), req.tableRef(),
                req.singleLimitMinor() == null ? 0 : req.singleLimitMinor(),
                req.dailyLimitMinor() == null ? 0 : req.dailyLimitMinor())));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_QR + "')")
    @PutMapping("/{qrCode}/disabled")
    public void disable(@PathVariable String qrCode) {
        Long storeId = ctx.storeId();
        codes.disable(ctx.caller(storeId), qrCode);
    }

    private QrView view(PayQrCode c) {
        return new QrView(c.getQrCode(), c.getKind(), c.getTableRef(), c.getCurrency(), c.getSingleLimitMinor(),
                c.getDailyLimitMinor(), c.getStatus(), checkoutBase + "/q/" + c.getQrCode());
    }
}
