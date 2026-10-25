package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.IssueScope;

import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Activate;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Stop;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.View;
import java.util.List;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueReleaseService {
    Check check(Prepare c);

    View prepare(Prepare c);

    View activate(Activate c);

    View stop(Stop c);

    public record Evidence(IssueScope scope,String entityNo,String submittedBy,String draftHash,String policyHash,boolean approvalRequired,List<String> versions,Prepare original) {public Evidence{versions=List.copyOf(versions);}}
}
