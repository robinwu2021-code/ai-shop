package ai.neargo.shop.pay.service;

import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import ai.neargo.shop.spi.settle.SettlePort;

/** Shared ledger and gateway initiation; identity facts must come from the authenticated core owner. */
public interface OrdinaryPaymentInitiationService {
    SettlePort.PayInitResult initiate(SettlePort.PaymentOpen request, PayerIdentity payer);
}
