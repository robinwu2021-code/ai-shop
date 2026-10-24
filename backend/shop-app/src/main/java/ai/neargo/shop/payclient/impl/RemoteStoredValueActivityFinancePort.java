package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.promotion.service.StoredValueActivityFinancePort;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueActivityFinancePort implements StoredValueActivityFinancePort {
 private final ai.neargo.shop.pay.client.PayInternalApi target;private final StoredValuePolicyAppService policies;
 public RemoteStoredValueActivityFinancePort(ai.neargo.shop.pay.client.PayInternalApi target,StoredValuePolicyAppService policies){this.target=target;this.policies=policies;}
 public EffectivePolicy evaluate(StoreScope scope){return policies.evaluate(scope);}
 public Check check(Prepare c){return RemoteEntitlementCalls.call(()->target.svReleaseCheck(c));}
 public View prepare(Prepare c){return RemoteEntitlementCalls.call(()->target.svReleasePrepare(c));}
 public View activate(Activate c){return RemoteEntitlementCalls.call(()->target.svReleaseActivate(c));}
 public View stop(Stop c){return RemoteEntitlementCalls.call(()->target.svReleaseStop(c));}
}
