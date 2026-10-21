package ai.neargo.shop.merchant.brand;
import org.junit.jupiter.api.Test;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
class BrandBindingConcurrencyTest {
 private record Setup(BrandFixture f,BrandService brands,String management,String brand){}
 private Setup setup() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","STORE","S1","store.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","STORE","S1","store.brand.change",null,null,false,null,false));
  var brands=new BrandService(f.repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json),
    (a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());
  var b=brands.create(f.owner,new BrandCreate(m,"示例品牌",null,"ACTIVE"),"brand-create");return new Setup(f,brands,m,f.no(b,"brandNo"));
 }
 private ChangeSubmit command(Setup s){
  var check=s.brands.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand));
  assertThat(check).containsEntry("canDecide",true);
  return new ChangeSubmit(s.management,s.brand,(String)check.get("checkToken"),0,0,null,0L,"加入品牌");
 }
 @Test void simultaneousRequestsAllowOnlyOnePendingAndActiveBinding() throws Exception {
  var s=setup();var c=command(s);var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var tasks=new ArrayList<Future<Map<String,Object>>>();
   for(int i=0;i<2;i++){final String key="change-"+i;tasks.add(pool.submit(()->{gate.await();try{return s.brands.submit(s.f.owner,"S1",c,key);}catch(ai.neargo.shop.common.BizException e){return Map.of();}}));}
   gate.countDown();var results=new ArrayList<Map<String,Object>>();for(var t:tasks)results.add(t.get(10,TimeUnit.SECONDS));
   assertThat(results.stream().filter(x->!x.isEmpty()).count()).isEqualTo(1);
   var result=results.stream().filter(x->!x.isEmpty()).findFirst().orElseThrow();
   s.brands.decide(s.f.owner,s.f.no(result,"changeNo"),new ChangeDecision("APPROVE",0,"确认"),"decision-1");
   assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",Long.class)).isEqualTo(1);
   assertThat(s.f.jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no='S1'",String.class)).isEqualTo("E1");
  }finally{pool.shutdownNow();}
 }
 @Test void replayReturnsOriginalButChangedCommandConflicts() throws Exception {
  var s=setup();var c=command(s);var original=s.brands.submit(s.f.owner,"S1",c,"same");
  assertThat(s.brands.change(s.f.owner,s.f.no(original,"changeNo")).get("actions")).asList().contains("brand.change.decide");
  assertThat(s.brands.submit(s.f.owner,"S1",c,"same")).containsEntry("changeNo",original.get("changeNo"));
  assertThatThrownBy(()->s.brands.submit(s.f.owner,"S1",new ChangeSubmit(s.management,null,c.checkToken(),0,0,null,null,"其他"),"same")).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void staleTokenAndUnknownOrFinancialImpactsNeverApply() throws Exception {
  var s=setup();var c=command(s);s.f.jdbc.update("UPDATE mch_store SET version=1 WHERE store_no='S1'");
  assertThatThrownBy(()->s.brands.submit(s.f.owner,"S1",c,"stale")).isInstanceOf(ai.neargo.shop.common.BizException.class);
  var unknown=new BrandService(s.f.repo,s.f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",s.f.repo.json),
    (a,e,st)->new BrandChangeImpactPort.Impact(false,false,0,0,0,0),s.f.numbers());
  assertThat(unknown.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand))).containsEntry("allowed",false);
  assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding",Long.class)).isZero();
 }
 @Test void originalSubmitAndDecisionCanReplayAfterBindingHasChanged() throws Exception {
  var s=setup();var c=command(s);var original=s.brands.submit(s.f.owner,"S1",c,"original");var no=s.f.no(original,"changeNo");
  var decision=new ChangeDecision("APPROVE",0,"确认");s.brands.decide(s.f.owner,no,decision,"approved");
  assertThat(s.brands.submit(s.f.owner,"S1",c,"original")).containsEntry("changeNo",no);
  var unavailable=new BrandService(s.f.repo,s.f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",s.f.repo.json),(a,e,st)->{throw new IllegalStateException("Pay unavailable");},s.f.numbers());
  assertThat(unavailable.decide(s.f.owner,no,decision,"approved")).containsEntry("status","COMPLETED");
 }

 @Test void currentStoreAndBrandVersionsAreFrozenInCheck() throws Exception {
  var s=setup();var c=command(s);
  var forged=new ChangeSubmit(s.management,s.brand,c.checkToken(),0,0,null,999L,"修改版本");
  assertThatThrownBy(()->s.brands.submit(s.f.owner,"S1",forged,"forged")).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
}
