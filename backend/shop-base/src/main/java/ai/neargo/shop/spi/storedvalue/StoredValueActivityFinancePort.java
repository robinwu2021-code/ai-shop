package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Activate;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Stop;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.View;

/** Calls occur outside Core transactions; business facts and permissions are resolved before invocation. */
public interface StoredValueActivityFinancePort {
 EffectivePolicy evaluate(StoreScope scope);
 Check check(Prepare command);
 View prepare(Prepare command);
 View activate(Activate command);
 View stop(Stop command);
}
