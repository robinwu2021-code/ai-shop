package ai.neargo.shop.promotion.service;
import ai.neargo.shop.promotion.service.impl.BrandActivityOwnershipServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
class StoredValueBrandOwnershipTest {
 @Test void storeApprovalPermissionCannotListOrReuseBrandApprovalCursor()throws Exception{
  var f=new StoredValueGovernanceFixture();
  var brandScope=new BrandScope("MAIN","M1","B1","I1","CNY",0);
  var actor=new Scope(brandScope,"E1","MCH:MA1","USER:P1",1);
  var rules=f.policy(true,"b".repeat(64));
  var policy=new EffectivePolicy(brandScope,rules.rules(),rules.sources(),rules.hash(),true);
  var submitted=f.journal.register(actor,f.view(),policy,"BRAND_SUBMIT");
  var onlyStore=f.journal.approvalPage("MAIN","M1",null,null,20,true,List.of());assertThat(onlyStore.records()).isEmpty();
  var ownBrand=f.journal.approvalPage("MAIN","M1",null,null,20,false,List.of("B1"));assertThat(ownBrand.records()).hasSize(1);
  assertThat(ownBrand.records().getFirst().scopeType()).isEqualTo("BRAND");assertThat(ownBrand.records().getFirst().scopeNo()).isEqualTo("B1");
  assertThatThrownBy(()->f.journal.approvalPage("MAIN","M1",null,submitted.approvalNo(),20,true,List.of())).isInstanceOf(BizException.class);
  assertThat(f.journal.approvalPage("MAIN","M1",null,null,20,false,List.of("OTHER")).records()).isEmpty();
 }
 @Test void activityOwnershipUsesBothManagementAndBrandAndIsWrittenInTheCoreTransaction()throws Exception{
  var f=new StoredValueGovernanceFixture();var owner=new BrandActivityOwnershipServiceImpl(new DataSourceTransactionManager(f.ds));
  var actor=new ActivityActor("MAIN","E1","ACTOR",null,"M1","B1");
  var row=new PmtActivity();row.setActivityNo("A1");row.setOwner("BRAND");row.setTenantNo("MAIN");row.setEntityNo("E1");
  assertThatThrownBy(()->owner.register(actor,"A1")).isInstanceOf(IllegalStateException.class);
  new TransactionTemplate(new DataSourceTransactionManager(f.ds)).execute(status->{owner.register(actor,"A1");return null;});
  assertThat(owner.visible(actor,row)).isTrue();
  assertThat(owner.visible(new ActivityActor("MAIN","E1","OTHER",null),row)).isFalse();
  assertThat(owner.visible(new ActivityActor("MAIN","E1","OTHER",null,"M1","OTHER"),row)).isFalse();
  assertThat(owner.visible(new ActivityActor("MAIN","E1","OTHER",null,"OTHER","B1"),row)).isFalse();
 }
}
