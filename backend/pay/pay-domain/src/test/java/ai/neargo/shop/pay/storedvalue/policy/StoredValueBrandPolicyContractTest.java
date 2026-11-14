package ai.neargo.shop.pay.storedvalue.policy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAllocationPolicy.LotBalance;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class StoredValueBrandPolicyContractTest {
 @Test void legacyStoreJsonKeepsItsShapeAndBothScopeTypesRoundTrip(){
  var json=new ObjectMapper();String old="{\"tenantNo\":\"MAIN\",\"managementNo\":\"M1\",\"storeNo\":\"S1\",\"brandNo\":null,\"currency\":\"CNY\",\"storeRevision\":0}";
  var store=json.readValue(old,IssueScope.class);assertThat(store).isEqualTo(new StoreScope("MAIN","M1","S1",null,"CNY",0));
  assertThat(json.readTree(json.writeValueAsString(store))).isEqualTo(json.readTree(old));
  var brand=new BrandScope("MAIN","M1","B1","I1","CNY",1);assertThat(json.readValue(json.writeValueAsString(brand),IssueScope.class)).isEqualTo(brand);
 }
 @Test void storeRulesCannotBecomeBrandRulesOrCrossIssuers(){
  var evaluator=new StoredValueIssuePolicyEvaluator();var scope=new BrandScope("MAIN","M1","B1","I1","CNY",0);
  var rules=new Rules(true,false,3000,100000,30000,365,1000000);
  var root=new PolicyVersion("MAIN","M1","P1","PV1","SPACE","M1","CNY",rules);
  var policy=evaluator.evaluate(scope,List.of(root));assertThat(policy.configured()).isTrue();
  assertThat(evaluator.check(policy,new IssueRequest(10000,3000,30,0,0)).allowed()).isTrue();
  assertThatThrownBy(()->evaluator.evaluate(scope,List.of(new PolicyVersion("MAIN","M1","P2","PV2","STORE","S1","CNY",rules)))).isInstanceOf(RuntimeException.class);
  assertThat(ai.neargo.shop.spi.storedvalue.StoredValuePolicies.sameIssueIdentity(scope,new BrandScope("MAIN","M1","B1","OTHER","CNY",0))).isFalse();
 }
 private UseSourceFact source(long amount){return new UseSourceFact("MAIN",new SourceRef("CORE","SERVICE_USE","ORDER",null,null,null,0),"USER","E1","S1","CNY",List.of(new UseItem("LINE","SERVICE","Care",1,amount)),"a".repeat(64),"Store",null,null);}
 @Test void proRataUsesOriginalBasisAndFinalAllocationConsumesRoundingResidue(){
  var allocator=new StoredValueAllocationPolicy();
  var lot=new LotBalance("L1",5,5,new RedemptionScope("ALL",List.of()),Instant.EPOCH,10L,5L);
  var split=allocator.allocate(List.of(lot),source(3)).allocations().getFirst();
  assertThat(split.principalMinor()).isEqualTo(2);assertThat(split.bonusMinor()).isEqualTo(1);
  var finalPart=allocator.allocate(List.of(lot),source(10)).allocations().getFirst();
  assertThat(finalPart.principalMinor()).isEqualTo(5);assertThat(finalPart.bonusMinor()).isEqualTo(5);
  var held=new LotBalance("L2",0,2,new RedemptionScope("ALL",List.of()),Instant.EPOCH,10L,5L);
  assertThat(allocator.allocate(List.of(held),source(2)).allocations().getFirst().bonusMinor()).isEqualTo(2);
 }
}
