package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService;
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

@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class EmbeddedStoredValueActivityFinancePort implements StoredValueActivityFinancePort {
 private final StoredValueReleaseService target;private final StoredValuePolicyAppService policies;
 public EmbeddedStoredValueActivityFinancePort(StoredValueReleaseService target,StoredValuePolicyAppService policies){this.target=target;this.policies=policies;}
 public EffectivePolicy evaluate(StoreScope scope){return policies.evaluate(scope);}
 public Check check(Prepare c){return target.check(c);}
 public View prepare(Prepare c){return target.prepare(c);}
 public View activate(Activate c){return target.activate(c);}
 public View stop(Stop c){return target.stop(c);}
}
