package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.user.UserIdentityPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves payer using core's authenticated session-aware owner before crossing process boundaries. */
@Component
public class OrdinaryPaymentPayerResolver {
    private final UserIdentityPort identities;
    private final String appId;

    public OrdinaryPaymentPayerResolver(UserIdentityPort identities,
            @Value("${shop.pay.wechat.appid:}") String appId) {
        this.identities = identities;
        this.appId = appId;
    }

    public PayerIdentity resolve(SettlePort.PaymentOpen request) {
        String payer = identities.wxPayerOpenIdMp(request.userNo()).orElse(null);
        return new PayerIdentity(request.userNo(), request.orderNo(), payer,
                "WECHAT".equals(request.payChannel()) ? appId : null, PayOrdinaryPaymentRequests.hash(request));
    }
}
