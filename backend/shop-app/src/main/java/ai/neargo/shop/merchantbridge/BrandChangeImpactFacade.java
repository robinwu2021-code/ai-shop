package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.merchant.brand.*;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.service.StoredValueOrganizationImpactService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.storedvalue.StoredValueOrganizationImpactPort;
import ai.neargo.shop.common.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Acquires Pay-owned evidence outside Merchant transactions. Transport failure fails closed. */
@Component
public class BrandChangeImpactFacade implements BrandChangeImpactPort {
 private final PayInternalApi remote;private final ObjectProvider<StoredValueOrganizationImpactService> local;private final boolean standalone;
 public BrandChangeImpactFacade(ObjectProvider<PayInternalApi> remote,ObjectProvider<StoredValueOrganizationImpactService> local,@Value("${shop.pay.deployment:embedded}") String mode){this.remote=remote.getIfAvailable();this.local=local;this.standalone="standalone".equals(mode);}
 public Impact inspect(ManagementModels.Actor actor,String entity,String store){
  var facts=standalone?RemoteEntitlementCalls.call(()->remote.svOrganizationImpact(new PayStoredValueMerchantRequests.OrganizationImpact(actor.tenantNo(),entity,store))):local.getObject().inspect(actor.tenantNo(),entity,store);
  if(facts==null||!actor.tenantNo().equals(facts.tenantNo())||!entity.equals(facts.entityNo())||!store.equals(facts.storeNo()))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
  return new Impact(facts.complete(),facts.brandFinancialEnabled(),facts.outstandingMinor(),facts.heldMinor(),facts.pendingRecharges(),facts.unattributedAccounts());
 }
}
