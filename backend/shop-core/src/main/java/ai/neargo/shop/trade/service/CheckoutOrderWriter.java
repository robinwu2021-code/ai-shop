package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.trade.order.service.create.CheckoutEconomicSnapshot;
import ai.neargo.shop.trade.order.service.create.CheckoutPricingScope;
import ai.neargo.shop.trade.order.service.create.ExpectedCheckoutQuote;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan;
import ai.neargo.shop.trade.order.service.create.CounterPurchaseAuthorization;

/**
 * The ordinary writer owns both preview and final pricing; checkout adds no second price engine.
 */
public interface CheckoutOrderWriter {
    CheckoutEconomicSnapshot quoteManualFor(
            CustomerParty party,
            OrderService.CreateOrderCommand command,
            CheckoutPricingScope scope);

    CounterOrderPlan stageCounterConfirmedFor(
            CustomerParty party,
            OrderService.CreateOrderCommand command,
            String intentKey,
            CounterPurchaseAuthorization authorization);

    OrderVO createCounterConfirmedFor(
            CustomerParty party, CounterOrderPlan plan, CounterPurchaseAuthorization authorization);

    /** Calculate explicit buyer evidence without registering stock or order creation effects. */
    CheckoutEconomicSnapshot quoteFor(
            String buyerNo, OrderService.CreateOrderCommand command, CheckoutPricingScope scope);

    /** Reuse actual final pricing and seal it before the existing creation intent is registered. */
    OrderVO createConfirmedFor(
            String buyerNo,
            OrderService.CreateOrderCommand command,
            String idempotencyKey,
            ExpectedCheckoutQuote expected);
}
