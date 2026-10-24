package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.port.OrdinaryPaymentPortImpl;
import ai.neargo.shop.pay.port.OrdinaryRefundPortImpl;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentInitiationPort;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class OrdinaryPaymentRouting {
    @Bean
    OrdinaryPaymentPort ordinaryPaymentPort(PaymentLedgerService ledger, PayQueryPort channel) {
        return new OrdinaryPaymentPortImpl(ledger, channel);
    }
    @Bean
    OrdinaryPaymentInitiationPort ordinaryPaymentInitiationPort(OrdinaryPaymentInitiationService service,
            OrdinaryPaymentPayerResolver payer) {
        return request -> service.initiate(request, payer.resolve(request));
    }
    @Bean
    OrdinaryRefundPort ordinaryRefundPort(PaymentLedgerService ledger, RefundExecutionService refunds) {
        return new OrdinaryRefundPortImpl(ledger, refunds);
    }
}
