package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.common.PayChannels;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import java.util.Objects;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Shared ordinary initiation; settlement generation and refund projections remain app orchestration. */
@Service
public class OrdinaryPaymentInitiationServiceImpl implements OrdinaryPaymentInitiationService {
    private final PaymentLedgerService ledger;
    private final PayGatewayRouter gateways;
    /** 渠道管线（TDD-支付服务-I2 S4）；手工构造的单测里没有，直调原网关 */
    private LegacyChannelBridge channels;

    public OrdinaryPaymentInitiationServiceImpl(PaymentLedgerService ledger, PayGatewayRouter gateways) {
        this.ledger = ledger;
        this.gateways = gateways;
    }

    @Autowired(required = false)
    void setChannels(LegacyChannelBridge channels) {
        this.channels = channels;
    }

    @Override
    public SettlePort.PayInitResult initiate(SettlePort.PaymentOpen request, PayerIdentity payer) {
        if (request == null || payer == null
                || !Objects.equals(request.userNo(), payer.userNo())
                || !Objects.equals(request.orderNo(), payer.orderNo())
                || !Objects.equals(PayOrdinaryPaymentRequests.hash(request), payer.requestHash())) {
            throw new IllegalArgumentException("PAY_PAYER_CONTEXT_CONFLICT");
        }
        if ("WECHAT".equals(request.payChannel())
                && (payer.openId() == null || payer.openId().isBlank()
                    || payer.appId() == null || payer.appId().isBlank())) {
            throw new IllegalArgumentException("PAY_PAYER_IDENTITY_REQUIRED");
        }
        String outTradeNo = ledger.open(request);
        var frozen = ledger.paymentRequest(outTradeNo).orElseThrow(
                () -> new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_REQUIRED"));
        if ("WECHAT".equals(frozen.payChannel())) {
            ledger.freezePayer(outTradeNo, frozen.userNo(), payer.openId(), payer.appId());
        }
        PayGateway gateway;
        try {
            gateway = gateways.of(frozen.payChannel());
        } catch (RuntimeException missingGateway) {
            return new SettlePort.PayInitResult(false, outTradeNo, frozen.payChannel(),
                    Map.of(), "支付通道未接入：" + frozen.payChannel());
        }
        String payerId = payer.openId();
        String subMchId = DirectAccounts.of(frozen).map(r -> r.subMerchantId()).orElse(null);
        // A thrown or ambiguous response leaves the same original intent pending for query/recovery.
        var command = new PayGateway.PrepayCommand(outTradeNo, frozen.amountMinor(),
                frozen.currency(), frozen.subject(), "JSAPI", subMchId, payerId);
        var response = channels == null ? gateway.prepay(command) : channels.prepay(frozen.payChannel(), command);
        if (response == null) {
            throw new IllegalStateException("PAY_CHANNEL_RESPONSE_MISSING");
        }
        if (!response.success()) {
            // A local rejection on a reused intent cannot disprove an earlier uncertain dispatch.
            return new SettlePort.PayInitResult(false, outTradeNo, frozen.payChannel(), Map.of(), response.message());
        }
        ledger.recordPayer(outTradeNo, payerId, "WECHAT".equals(frozen.payChannel()) ? payer.appId() : null);
        if (PayChannels.FREE.equals(frozen.payChannel())) {
            ledger.settle(new SettlePort.PaymentSettled(outTradeNo, frozen.payChannel(), response.tradeNo(),
                    0L, System.currentTimeMillis(), frozen.currency()));
        }
        return new SettlePort.PayInitResult(true, outTradeNo, frozen.payChannel(), response.params(), null);
    }
}
