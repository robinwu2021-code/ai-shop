package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
public final class PayStoredValuePolicyRequests {
 private PayStoredValuePolicyRequests() { }
 public record PlanBinding(String tenant,String entity,String store,String version) { }
 public record Locate(String tenantNo,String policyNo) { }
 public record Configure(PolicyAuthority scope,PolicyEdit edit,String key) { }
}
