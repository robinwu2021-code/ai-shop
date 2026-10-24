package ai.neargo.shop.promotion.service;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
/** Calls occur outside Core transactions; business facts and permissions are resolved before invocation. */
public interface StoredValueActivityFinancePort {
 EffectivePolicy evaluate(StoreScope scope);
 Check check(Prepare command);
 View prepare(Prepare command);
 View activate(Activate command);
 View stop(Stop command);
}
