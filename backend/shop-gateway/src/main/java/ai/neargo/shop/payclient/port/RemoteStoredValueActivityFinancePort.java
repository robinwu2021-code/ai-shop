package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueActivityFinancePort;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Activate;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Stop;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.View;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueActivityFinancePort implements StoredValueActivityFinancePort {
 private final PayInternalApi target;private final StoredValuePolicyAppService policies;
 public RemoteStoredValueActivityFinancePort(PayInternalApi target,StoredValuePolicyAppService policies){this.target=target;this.policies=policies;}
 public EffectivePolicy evaluate(StoreScope scope){return policies.evaluate(scope);}
 public Check check(Prepare c){return RemoteEntitlementCalls.call(()->target.svReleaseCheck(c));}
 public View prepare(Prepare c){return RemoteEntitlementCalls.call(()->target.svReleasePrepare(c));}
 public View activate(Activate c){return RemoteEntitlementCalls.call(()->target.svReleaseActivate(c));}
 public View stop(Stop c){return RemoteEntitlementCalls.call(()->target.svReleaseStop(c));}
}
