package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.promotion.service.StoredValueActivityFinancePort;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class EmbeddedStoredValueActivityFinancePort implements StoredValueActivityFinancePort {
 private final ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService target;private final StoredValuePolicyAppService policies;
 public EmbeddedStoredValueActivityFinancePort(ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService target,StoredValuePolicyAppService policies){this.target=target;this.policies=policies;}
 public EffectivePolicy evaluate(StoreScope scope){return policies.evaluate(scope);}
 public Check check(Prepare c){return target.check(c);}
 public View prepare(Prepare c){return target.prepare(c);}
 public View activate(Activate c){return target.activate(c);}
 public View stop(Stop c){return target.stop(c);}
}
