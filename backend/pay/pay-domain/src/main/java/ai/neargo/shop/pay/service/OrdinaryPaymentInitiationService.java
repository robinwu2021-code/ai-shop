package ai.neargo.shop.pay.service;

import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PayInitResult;

/** Shared ledger and gateway initiation; identity facts must come from the authenticated core owner. */
public interface OrdinaryPaymentInitiationService {
    PayInitResult initiate(PaymentOpen request, PayerIdentity payer);

    /** Local preparation only, so route, attempt and payer can share the caller's short payment transaction. */
    String prepare(PaymentOpen request, PayerIdentity payer);
}
