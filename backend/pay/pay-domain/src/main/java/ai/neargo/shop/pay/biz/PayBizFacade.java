package ai.neargo.shop.pay.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.service.PayStatementService;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.checkout.service.CheckoutService;
import ai.neargo.shop.pay.checkout.service.CheckoutService.Issued;
import ai.neargo.shop.pay.checkout.service.QrCodeService;
import ai.neargo.shop.pay.checkout.service.QrCodeService.CreateQrCmd;
import ai.neargo.shop.pay.funds.BizDates;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService.StoreRef;
import ai.neargo.shop.pay.openapp.service.AppGrantService;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 商家端支付的全部动作（开发计划 M5a.5）：b-app 不再直连支付（{@code /biz/pay} 退役），而是
 * b-app → {@code shop-app} {@code /biz/payment/**}（认商家、判 {@code BizPerms.PAY_*}、取当前门店）→ {@code payclient} → 这里。
 * 内嵌形态由 {@code shop-app} 直调；独立形态由 {@code pay-svc} 的 {@code /internal/pay/v1/biz/**} 调 —— 同一份逻辑。
 *
 * <p><b>门店与操作人一律显式传入</b>：门店是业务服务按商家授权校验过的当前门店（F02，不接受端上入参），操作人是当前商家账号；
 * 调用方 = 收银台接入方名下、只圈这一家门店的商家操作员（{@link InternalCallers#merchantOperator}）。
 */
@Component
public class PayBizFacade {

    private final PayMerchantBindingService stores;
    private final InternalCallers callers;
    private final AppGrantService grants;
    private final CheckoutService checkout;
    private final QrCodeService codes;
    private final RefundService refunds;
    private final PayStatementService statements;
    private final String checkoutBase;

    public PayBizFacade(PayMerchantBindingService stores, InternalCallers callers, AppGrantService grants,
                        CheckoutService checkout, QrCodeService codes, RefundService refunds,
                        PayStatementService statements, @Value("${shop.pay.checkout.base-url:}") String checkoutBase) {
        this.stores = stores;
        this.callers = callers;
        this.grants = grants;
        this.checkout = checkout;
        this.codes = codes;
        this.refunds = refunds;
        this.statements = statements;
        this.checkoutBase = checkoutBase;
    }

    /** @param checkoutUrl 顾客扫的链接（前端据此出二维码）；令牌明文只在这一次响应里 */
    public record CollectView(String payOrderNo, String token, long expireAt, String checkoutUrl) {
    }

    /** @param url 顾客扫的链接（前端据此出二维码） */
    public record QrView(String qrCode, String kind, String tableRef, String currency, long singleLimitMinor,
                         long dailyLimitMinor, String status, String url) {
    }

    public record RefundView(String payRefundNo, Long payOrderId, long amountMinor, String currency, String status,
                             String reason, String requestedBy, Long createdAt, Long succeededAt) {

        static RefundView of(PayRefund r) {
            return new RefundView(r.getPayRefundNo(), r.getPayOrderId(), r.getAmountMinor() == null ? 0 : r.getAmountMinor(),
                    r.getCurrency(), r.getStatus(), r.getReason(), r.getRequestedBy(),
                    r.getCreatedAt() == null ? null : r.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    r.getSucceededAt() == null ? null : r.getSucceededAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
    }

    // ---- 授权第三方（F44） ----

    public List<GrantView> grants(String storeNo, String operatorNo) {
        StoreRef s = store(storeNo);
        return grants.ofStore(caller(s, operatorNo), s.storeId());
    }

    public GrantView grant(String storeNo, String operatorNo, String appNo, Set<String> actions) {
        StoreRef s = store(storeNo);
        return grants.grant(caller(s, operatorNo), s.storeId(), appNo, actions);
    }

    public void revokeGrant(String storeNo, String operatorNo, Long id) {
        grants.revoke(caller(store(storeNo), operatorNo), id);
    }

    // ---- 代客收款（F42） ----

    public CollectView collect(String storeNo, String operatorNo, Long amountMinor, String subject, Integer expireMinutes) {
        StoreRef s = store(storeNo);
        Issued i = checkout.collect(caller(s, operatorNo), s.storeId(), amountMinor == null ? 0 : amountMinor, subject,
                expireMinutes);
        return new CollectView(i.payOrderNo(), i.token(), i.expireAt(), checkoutBase + "/c/" + i.token());
    }

    // ---- 静态收款码（F45） ----

    public List<QrView> qrCodes(String storeNo, String operatorNo) {
        StoreRef s = store(storeNo);
        return codes.ofStore(caller(s, operatorNo), s.storeId()).stream().map(this::view).toList();
    }

    public QrView createQrCode(String storeNo, String operatorNo, String kind, String tableRef, Long singleLimitMinor,
                               Long dailyLimitMinor) {
        StoreRef s = store(storeNo);
        return view(codes.create(caller(s, operatorNo), s.storeId(), new CreateQrCmd(kind, tableRef,
                singleLimitMinor == null ? 0 : singleLimitMinor, dailyLimitMinor == null ? 0 : dailyLimitMinor)));
    }

    public void disableQrCode(String storeNo, String operatorNo, String qrCode) {
        codes.disable(caller(store(storeNo), operatorNo), qrCode);
    }

    // ---- 原路退款（F40） ----

    /** @param outRefundNo 可不传（每次点击各算一笔）；传了就按它幂等 —— 端上防连点时带同一个 */
    public RefundView requestRefund(String storeNo, String operatorNo, String payOrderNo, Long amountMinor, String reason,
                                    String outRefundNo) {
        if (payOrderNo == null || payOrderNo.isBlank() || amountMinor == null || amountMinor <= 0
                || (reason != null && reason.length() > 255)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String out = outRefundNo == null || outRefundNo.isBlank() ? BizKey.next("BZR") : outRefundNo;
        return RefundView.of(refunds.request(caller(store(storeNo), operatorNo),
                new RefundRequestCmd(payOrderNo, out, out, amountMinor, reason)));
    }

    public List<RefundView> refunds(String storeNo, String operatorNo) {
        StoreRef s = store(storeNo);
        return refunds.recentOfStore(caller(s, operatorNo), s.storeId(), 50).stream().map(RefundView::of).toList();
    }

    // ---- 收款日流水（F43） ----

    /** @param day {@code yyyy-MM-dd}；不传 = 门店市场的「今天」 */
    public DailyStatement statement(String storeNo, String operatorNo, String day) {
        StoreRef s = store(storeNo);
        LocalDate d;
        try {
            d = day == null || day.isBlank()
                    ? BizDates.of(s.market() == null ? "CN" : s.market(), Instant.now())
                    : LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return statements.daily(caller(s, operatorNo), s.storeId(), d);
    }

    private StoreRef store(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return stores.storeByNo(storeNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    /** 收银台接入方名下、本店、操作员 = 当前商家账号（没有操作员直接拒 —— 不能以系统身份动钱） */
    private PayCaller caller(StoreRef s, String operatorNo) {
        return callers.merchantOperator(InternalCallers.CHECKOUT, s.storeId(), operatorNo);
    }

    private QrView view(PayQrCode c) {
        return new QrView(c.getQrCode(), c.getKind(), c.getTableRef(), c.getCurrency(), c.getSingleLimitMinor(),
                c.getDailyLimitMinor(), c.getStatus(), checkoutBase + "/q/" + c.getQrCode());
    }
}
