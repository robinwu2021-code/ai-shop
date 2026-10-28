package ai.neargo.shop.pay.terminal.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentCmd;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService.StoreRef;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.terminal.service.InStorePaymentService.Charge;
import ai.neargo.shop.pay.terminal.service.InStorePaymentService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** {@link InStorePaymentService} 的实现（职责与约束见接口注释） */
@Service
public class InStorePaymentServiceImpl implements InStorePaymentService {

    static final int EXPIRE_MINUTES = 15;

    private final PaymentService payments;
    private final PayMerchantBindingService stores;
    private final String sandboxPayMethod;

    public InStorePaymentServiceImpl(PaymentService payments, PayMerchantBindingService stores,
                                 @Value("${shop.pay.terminal.sandbox-pay-method:}") String sandboxPayMethod) {
        this.payments = payments;
        this.stores = stores;
        this.sandboxPayMethod = sandboxPayMethod;
    }


    /** 终端在本店建一张门店收款单（门店取终端绑定的那一家，不接受入参） */
    @Override
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
    @Override
    public Charge chargeCard(PayCaller caller, String payOrderNo, String encryptedCardData) {
        if (encryptedCardData == null || encryptedCardData.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return charge(caller, payOrderNo);
    }

    /** 扫顾客付款码 */
    @Override
    public Charge chargeCustomerCode(PayCaller caller, String payOrderNo, String authCode) {
        if (authCode == null || authCode.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return charge(caller, payOrderNo);
    }

    /** 轮询：结果不明时终端必须先调它（查单补账，不关单） */
    @Override
    public Charge payment(PayCaller caller, String payOrderNo) {
        return Charge.of(payments.refresh(caller, payOrderNo));
    }

    /** 取消：关前必查单 —— 查到已付就不关，返回已付（「终端已扣、我方已关」两边都挡） */
    @Override
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
