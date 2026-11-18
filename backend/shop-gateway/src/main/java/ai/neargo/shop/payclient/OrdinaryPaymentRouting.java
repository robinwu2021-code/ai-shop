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
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.port.PaymentPortImpl;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.api.guard.internal.InternalCallRecorder;
import ai.neargo.shop.pay.api.guard.internal.port.RecordingPaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort;
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * 支付单（TDD-支付服务-I3支付单退款流水 S3）：内嵌形态直调支付域；独立形态由远程实现顶上（S8）。
     * 每次调用写一行 {@code INTERNAL_PORT} 请求记录（ADR-045 G9，开发计划 M2.4）—— 独立形态那边由内部入口的守卫记。
     */
    @Bean
    PaymentPort paymentPort(PaymentService payments, RefundService refunds, InternalCallers callers,
                            @Value("${shop.pay.order.enabled:true}") boolean enabled,
                            @Value("${shop.pay.guard.internal.record-port-calls:true}") boolean record,
                            InternalCallRecorder recorder) {
        PaymentPort port = new PaymentPortImpl(payments, refunds, callers, enabled);
        return record ? new RecordingPaymentPort(port, recorder) : port;
    }
}
