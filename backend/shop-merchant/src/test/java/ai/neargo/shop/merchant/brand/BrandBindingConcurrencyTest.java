package ai.neargo.shop.merchant.brand;
import ai.neargo.shop.spi.storedvalue.BrandChangeImpactPort;

import ai.neargo.shop.merchant.brand.impl.BrandServiceImpl;
import org.junit.jupiter.api.Test;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
/** 入退连锁当场生效、按权益准入（TDD-平台基础库换表「连锁按权益准入」，2026-11-28 用户定：不走审批）。 */
class BrandBindingConcurrencyTest {
 private record Setup(BrandFixture f,BrandService brands,String management,String brand){}
 private Setup setup() throws Exception {
  var f=new BrandFixture();var m=f.no(f.space(),"managementNo");
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","STORE","S1","store.read",null,null,false,null,false));
  f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","STORE","S1","store.brand.change",null,null,false,null,false));
  var brands=new BrandServiceImpl(f.repo,f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",f.repo.json),
    (a,e,s)->new BrandChangeImpactPort.Impact(true,false,0,0,0,0),f.numbers());
  var b=brands.create(f.owner,new BrandCreate(m,"示例品牌",null,"ACTIVE"),"brand-create");return new Setup(f,brands,m,f.no(b,"brandNo"));
 }
 private ChangeSubmit command(Setup s){
  var check=s.brands.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand));
  assertThat(check).containsEntry("allowed",true);
  return new ChangeSubmit(s.management,s.brand,(String)check.get("checkToken"),0,0,null,0L,"加入品牌");
 }
 @Test void simultaneousRequestsAllowOnlyOneActiveBinding() throws Exception {
  var s=setup();var c=command(s);var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var tasks=new ArrayList<Future<Map<String,Object>>>();
   for(int i=0;i<2;i++){final String key="change-"+i;tasks.add(pool.submit(()->{gate.await();try{return s.brands.submit(s.f.owner,"S1",c,key);}catch(ai.neargo.shop.common.BizException e){return Map.of();}}));}
   gate.countDown();var results=new ArrayList<Map<String,Object>>();for(var t:tasks)results.add(t.get(10,TimeUnit.SECONDS));
   assertThat(results.stream().filter(x->!x.isEmpty()).count()).isEqualTo(1);
   var result=results.stream().filter(x->!x.isEmpty()).findFirst().orElseThrow();
   assertThat(result).containsEntry("applied",true).containsEntry("admissionBasis","LEGACY");
   assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",Long.class)).isEqualTo(1);
   assertThat(s.f.jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no='S1'",String.class)).isEqualTo("E1");
  }finally{pool.shutdownNow();}
 }
 @Test void replayReturnsOriginalButChangedCommandConflicts() throws Exception {
  var s=setup();var c=command(s);var original=s.brands.submit(s.f.owner,"S1",c,"same");
  assertThat(original).containsEntry("applied",true);
  assertThat(s.f.jdbc.queryForObject("SELECT active_store_no FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",String.class)).isEqualTo("S1");
  assertThat(s.brands.submit(s.f.owner,"S1",c,"same")).containsEntry("changeNo",original.get("changeNo"));
  assertThatThrownBy(()->s.brands.submit(s.f.owner,"S1",new ChangeSubmit(s.management,null,c.checkToken(),0,0,null,null,"其他"),"same")).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void staleTokenAndUnknownOrFinancialImpactsNeverApply() throws Exception {
  var s=setup();var c=command(s);s.f.jdbc.update("UPDATE mch_store SET version=1 WHERE store_no='S1'");
  assertThatThrownBy(()->s.brands.submit(s.f.owner,"S1",c,"stale")).isInstanceOf(ai.neargo.shop.common.BizException.class);
  var unknown=new BrandServiceImpl(s.f.repo,s.f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",s.f.repo.json),
    (a,e,st)->new BrandChangeImpactPort.Impact(false,false,0,0,0,0),s.f.numbers());
  assertThat(unknown.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand))).containsEntry("allowed",false);
  var fresh=new ChangeSubmit(s.management,s.brand,(String)unknown.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand)).get("checkToken"),1,0,null,0L,"加入品牌");
  assertThat(unknown.submit(s.f.owner,"S1",fresh,"unknown")).containsEntry("applied",false);
  assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding",Long.class)).isZero();
 }
 @Test void originalSubmitReplaysAfterBindingHasChanged() throws Exception {
  var s=setup();var c=command(s);var original=s.brands.submit(s.f.owner,"S1",c,"original");var no=s.f.no(original,"changeNo");
  // 已改绑后影响检查依赖不可用，重放仍返回原结果（不重算）
  var unavailable=new BrandServiceImpl(s.f.repo,s.f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",s.f.repo.json),(a,e,st)->{throw new IllegalStateException("Pay unavailable");},s.f.numbers());
  assertThat(unavailable.submit(s.f.owner,"S1",c,"original")).containsEntry("changeNo",no).containsEntry("applied",true);
 }

 @Test void currentStoreAndBrandVersionsAreFrozenInCheck() throws Exception {
  var s=setup();var c=command(s);
  var forged=new ChangeSubmit(s.management,s.brand,c.checkToken(),0,0,null,999L,"修改版本");
  assertThatThrownBy(()->s.brands.submit(s.f.owner,"S1",forged,"forged")).isInstanceOf(ai.neargo.shop.common.BizException.class);
 }
 @Test void impactBlockerNeverApplies() throws Exception {
  var s=setup();var c=command(s);
  var indebted=new BrandServiceImpl(s.f.repo,s.f.scopes,new ManagementTokenCodec("0123456789abcdefghijklmnopqrstuvwxyz-secret",s.f.repo.json),(a,e,st)->new BrandChangeImpactPort.Impact(true,false,1,0,0,0),s.f.numbers());
  var result=indebted.submit(s.f.owner,"S1",c,"after-debt");
  assertThat(result).containsEntry("applied",false);assertThat(result.get("blockers")).asList().containsExactly("STORED_VALUE_UNRESOLVED");
  assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding",Long.class)).isZero();
 }
 @Test void newModelStoreNeedsChainEntitlement() throws Exception {
  var s=setup();s.f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1' WHERE store_no='S1'");
  var check=s.brands.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand));
  assertThat(check).containsEntry("allowed",false).containsEntry("nextAction","BUY");
  assertThat(check.get("blockers")).asList().containsExactly("CHAIN_ENTITLEMENT_REQUIRED");
  var c=new ChangeSubmit(s.management,s.brand,(String)check.get("checkToken"),0,0,null,0L,"加入品牌");
  assertThat(s.brands.submit(s.f.owner,"S1",c,"no-entitlement")).containsEntry("applied",false);
  assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding",Long.class)).isZero();
  // 过期的、撤销的都不算
  long now=System.currentTimeMillis();
  grant(s,"G1",now-200_000,now-100_000,"ENDED");grant(s,"G2",now-100_000,null,"REVOKED");
  assertThat(s.brands.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand))).containsEntry("allowed",false);
  grant(s,"G3",now-100_000,now+86_400_000L,"ACTIVE");
  check=s.brands.check(s.f.owner,"S1",new ChangeCheck(s.management,s.brand));
  assertThat(check).containsEntry("allowed",true);
  @SuppressWarnings("unchecked") var admission=(Map<String,Object>)check.get("admission");assertThat(admission).containsEntry("basis","ENTITLEMENT").containsEntry("entitledUntil",now+86_400_000L);
  var applied=s.brands.submit(s.f.owner,"S1",new ChangeSubmit(s.management,s.brand,(String)check.get("checkToken"),0,0,null,0L,"加入品牌"),"entitled");
  assertThat(applied).containsEntry("applied",true).containsEntry("admissionBasis","ENTITLEMENT");
  assertThat(s.f.jdbc.queryForObject("SELECT admission_basis FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",String.class)).isEqualTo("ENTITLEMENT");
 }
 @Test void leavingTheBrandNeedsNoEntitlement() throws Exception {
  var s=setup();s.brands.submit(s.f.owner,"S1",command(s),"join");
  s.f.jdbc.update("UPDATE mch_store SET commerce_model='STORE_V1' WHERE store_no='S1'");
  var check=s.brands.check(s.f.owner,"S1",new ChangeCheck(s.management,null));
  assertThat(check).containsEntry("allowed",true);
  long storeVersion=((Number)check.get("storeVersion")).longValue(),binding=((Number)check.get("bindingRevision")).longValue(),from=((Number)check.get("fromRevision")).longValue();
  var left=s.brands.submit(s.f.owner,"S1",new ChangeSubmit(s.management,null,(String)check.get("checkToken"),storeVersion,binding,from,null,"退出品牌"),"leave");
  assertThat(left).containsEntry("applied",true);
  assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding WHERE store_no='S1' AND status='ACTIVE'",Long.class)).isZero();
  assertThat(s.f.jdbc.queryForObject("SELECT count(*) FROM mch_brand_store_binding WHERE store_no='S1' AND active_store_no IS NOT NULL",Long.class)).isZero();
 }
 private static void grant(Setup s,String no,long start,Long end,String state){
  s.f.jdbc.update("INSERT INTO mch_store_end_grant(tenant_no,grant_no,store_no,end_code,entitlement_code,start_at,end_at,state) VALUES('MAIN',?,'S1','CHAIN','END_CHAIN',?,?,?)",no,start,end,state);
 }
 @Test void revocationCommittedWhileMutationWaitsIsObserved() throws Exception {
  org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("p1.mysql.url")!=null,"MySQL repeatable-read regression");
  var s=setup();var staff=new Actor("MAIN","MCH:MA3");
  s.f.scopes.grant(s.f.owner,s.management,new GrantCommand(staff.actorKey(),"SPACE",s.management,"management.enter",null,null,false,null,false));
  var grant=s.f.scopes.grant(s.f.owner,s.management,new GrantCommand(staff.actorKey(),"BRAND",s.brand,"brand.manage",null,null,false,null,false));
  var started=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
  try{var future=s.f.repo.write(()->{
   s.f.scopes.lock(s.f.owner,s.management);
   var task=pool.submit(()->{started.countDown();return s.brands.edit(staff,s.brand,new BrandEdit("不得修改",null,0),"revoked-edit");});
   try{started.await();Thread.sleep(300);}catch(InterruptedException e){throw new RuntimeException(e);}
   s.f.scopes.revoke(s.f.owner,s.management,(String)grant.get("grantNo"),0,"撤回授权");return task;
  });
  assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ai.neargo.shop.common.BizException.class);
  assertThat(s.brands.detail(s.f.owner,s.brand)).containsEntry("name","示例品牌");
  }finally{pool.shutdownNow();}
 }
}
