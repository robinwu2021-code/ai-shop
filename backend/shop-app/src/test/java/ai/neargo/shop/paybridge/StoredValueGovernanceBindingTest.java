package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueGovernanceBindingTest {
 @Test void freshCoreActivationAndOrganizationFactsAreRequiredForNewPlanBinding(){
  var pay=mock(StoredValuePolicyAppService.class);var facts=mock(StoredValuePolicyFactsPort.class);var scope=new StoreScope("MAIN","M1","S1",null,"CNY");
  when(pay.planBinding("MAIN","E1","S1","PV1")).thenReturn(new PlanBinding(scope,"E1","AV1","PV1"));
  var target=new StoredValuePlanGovernanceBinding(pay,facts,true);
  assertThatThrownBy(()->target.resolve("MAIN","E1","S1","PV1")).isInstanceOf(BizException.class);
  when(facts.current("MAIN","E1","S1","AV1")).thenReturn(scope);assertThat(target.resolve("MAIN","E1","S1","PV1")).isEqualTo(scope);
  when(facts.current("MAIN","E1","S1","AV1")).thenReturn(new StoreScope("MAIN","M2","S1",null,"CNY"));assertThatThrownBy(()->target.resolve("MAIN","E1","S1","PV1")).isInstanceOf(BizException.class);
 }
 @Test void disabledGovernancePreservesLegacyBindingWithoutRemoteLookups(){var pay=mock(StoredValuePolicyAppService.class);var facts=mock(StoredValuePolicyFactsPort.class);assertThat(new StoredValuePlanGovernanceBinding(pay,facts,false).resolve("MAIN","E1","S1","PV1")).isNull();verifyNoInteractions(pay,facts);}
}
