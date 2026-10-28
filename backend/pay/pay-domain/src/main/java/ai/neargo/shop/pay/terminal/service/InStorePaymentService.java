package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentCmd;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService.StoreRef;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 门店收款编排（pay-guard 开发计划 M6.4；代码清单 §4.2 {@code InStorePaymentService}）：终端建单 → 刷卡 / 扫顾客付款码 →
 * 结果不明记 {@code UNKNOWN} → 终端轮询查单补账 → 关前必查单。
 *
 * <p><b>同步扣款结果不明时绝不判失败</b>：卡已经刷了、渠道还没回，判失败就是「顾客被扣了钱、店里说没付」。
 * 返回 {@code UNKNOWN}，终端必须调 {@link #payment} 查到结论为止。
 *
 * <p><b>沙箱</b>：刷卡真渠道要等读卡套件规格（代码清单 Q12），今天刷卡与扫码都经 {@code shop.pay.terminal.sandbox-pay-method}
 * 指定的方式（联调用 {@code TEST_PAY}）发起；没配就拒 —— 生产不配，生产环境刷卡因此不会误走测试通道。加密卡数据原样透传、不落日志。
 */
@Service
public class InStorePaymentService {

    static final int EXPIRE_MINUTES = 15;

    private final PaymentService payments;
    private final PayMerchantBindingService stores;
    private final String sandboxPayMethod;

    public InStorePaymentService(PaymentService payments, PayMerchantBindingService stores,
                                 @Value("${shop.pay.terminal.sandbox-pay-method:}") String sandboxPayMethod) {
        this.payments = payments;
        this.stores = stores;
        this.sandboxPayMethod = sandboxPayMethod;
    }

    /** @param status {@code SUCCEEDED} / {@code UNKNOWN}（结果不明，必须查单）/ {@code CLOSED} / 支付单的其它状态 */
    public record Charge(String payOrderNo, String status, long amountMinor, String currency) {

        static Charge of(PayOrder o) {
            String s = o.getStatus();
            String view = "SUCCEEDED".equals(s) || "PARTIALLY_REFUNDED".equals(s) || "REFUNDED".equals(s) || "CLOSED".equals(s)
                    ? s : "AWAITING_PAYMENT".equals(s) ? "UNKNOWN" : s;
            return new Charge(o.getPayOrderNo(), view, o.getAmountMinor() == null ? 0 : o.getAmountMinor(), o.getCurrency());
        }
    }

    /** 终端在本店建一张门店收款单（门店取终端绑定的那一家，不接受入参） */
    public Charge create(PayCaller caller, String outOrderNo, long amountMinor, String currency, String subject) {
        if (outOrderNo == null || outOrderNo.isBlank() || amountMinor <= 0 || caller.storeScope().size() != 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Long storeId = caller.storeScope().iterator().next();
        StoreRef store = stores.storeById(storeId).orElseThrow(() -> BizException.of(ErrorCode.PAY_FORBIDDEN));
        if (!MerchantDefaults.currencyOf(store.market()).equals(currency)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PayOrder o = payments.create(caller, new CreatePaymentCmd("IN_STORE", outOrderNo, outOrderNo, storeId, store.storeName(),
                store.entityName(), store.market(), amountMinor, currency, subject, "IN_STORE",
                Instant.now().plus(EXPIRE_MINUTES, ChronoUnit.MINUTES), store.fundsPath()));
        return Charge.of(o);
    }

    /** 刷卡：{@code encryptedCardData} 是读卡套件的密文，不解析、不落日志 */
    public Charge chargeCard(PayCaller caller, String payOrderNo, String encryptedCardData) {
        if (encryptedCardData == null || encryptedCardData.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return charge(caller, payOrderNo);
    }

    /** 扫顾客付款码 */
    public Charge chargeCustomerCode(PayCaller caller, String payOrderNo, String authCode) {
        if (authCode == null || authCode.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return charge(caller, payOrderNo);
    }

    /** 轮询：结果不明时终端必须先调它（查单补账，不关单） */
    public Charge payment(PayCaller caller, String payOrderNo) {
        return Charge.of(payments.refresh(caller, payOrderNo));
    }

    /** 取消：关前必查单 —— 查到已付就不关，返回已付（「终端已扣、我方已关」两边都挡） */
    public Charge cancel(PayCaller caller, String payOrderNo) {
        return Charge.of(payments.close(caller, payOrderNo, "TERMINAL_CANCEL"));
    }

    private Charge charge(PayCaller caller, String payOrderNo) {
        if (sandboxPayMethod == null || sandboxPayMethod.isBlank()) {
            throw BizException.of(ErrorCode.PAY_ORDER_STATE, "IN_STORE_CHANNEL_NOT_CONFIGURED");
        }
        payments.start(caller, payOrderNo, new StartPaymentCmd(sandboxPayMethod, null, null));
        return Charge.of(payments.refresh(caller, payOrderNo));
    }
}
