package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;

import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage;

import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;

import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;

/** Domain contract; transactional behavior remains in the implementation. */
public interface EntitlementEventDeliveryService {
    DeadLetterPage deadLetters(ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope scope,String cursor,int limit);
    ExceptionView captureDead(String eventNo);
    void resolveSent(String eventNo);
    ExceptionView retryEvent(CatalogScope scope,String no,RecoveryCommand command,String key);
    ExceptionView queryEvent(CatalogScope scope,String no,RecoveryCommand command,String key);
}
