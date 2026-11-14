package ai.neargo.shop.pay.storedvalue.policy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyEdit;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StoredValueReleaseFlowTest {
 @Test void approvalAndChangedPolicyCannotBeBypassed()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(true,2000,100000));var prepared=f.releases.prepare(f.command(10000,2000));
  assertThatThrownBy(()->f.releases.activate(f.activation(prepared,null))).isInstanceOf(BizException.class);
  var proof=new Approval("a".repeat(64),prepared.policyHash(),"SUBMITTER","APPROVER");
  f.configs.configure(f.owner,new PolicyEdit(f.rules(true,1000,100000),0),"TIGHTEN");
  assertThatThrownBy(()->f.releases.activate(f.activation(prepared,proof))).isInstanceOf(BizException.class);
  assertThat(f.jdbc.queryForObject("SELECT state FROM sv_release",String.class)).isEqualTo("PREPARED");
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge_plan",Long.class)).isZero();
 }
 @Test void repeatedActivationProjectsOnlyOnceAndDelayedAckCannotReopenStoppedOffer()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(false,2000,100000));var command=f.command(10000,2000);var p=f.releases.prepare(command);var active=f.releases.activate(f.activation(p,null));
  assertThat(f.releases.activate(f.activation(p,null))).isEqualTo(active);
  assertThat(f.releases.prepare(command).releaseNo()).isEqualTo(active.releaseNo());
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge_plan_version",Long.class)).isEqualTo(1);
  f.releases.stop(new Stop(f.scope,"E1","OWNER",p.releaseNo(),"STOP"));
  assertThat(f.releases.activate(f.activation(p,null)).state()).isEqualTo("ISSUE_STOPPED");
  assertThat(f.jdbc.queryForObject("SELECT status FROM sv_recharge_plan",String.class)).isEqualTo("INACTIVE");
 }
 @Test void customerWithoutConsumerLoginReservesOriginalTierAndTightenedLimitRollsBack()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(false,2000,20000));f.activate(10000,2000);f.recharge("R1",10000,2000);
  assertThat(f.jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(12000);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_issue_quota_reservation",Long.class)).isEqualTo(1);
  assertThatThrownBy(()->f.recharge("R2",10000,2000)).isInstanceOf(BizException.class);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge",Long.class)).isEqualTo(1);
  assertThat(f.jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(12000);
 }
 @Test void concurrentIssuanceCannotOverReserveSharedStoreQuota()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(false,0,5000));f.activate(3000,0);var pool=Executors.newFixedThreadPool(2);var success=new AtomicInteger();
  try{var one=CompletableFuture.runAsync(()->{try{f.recharge("R1",3000,0);success.incrementAndGet();}catch(BizException denied){}},pool);var two=CompletableFuture.runAsync(()->{try{f.recharge("R2",3000,0);success.incrementAndGet();}catch(BizException denied){}},pool);CompletableFuture.allOf(one,two).get(15,TimeUnit.SECONDS);}
  finally{pool.shutdownNow();}
  assertThat(success.get()).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(3000);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge",Long.class)).isEqualTo(1);
 }
 @Test void sourceRevisionFenceRejectsStaleFactsWhileSameStoreCanAdoptNewBrandPolicy()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(false,2000,100000));f.activate(10000,2000);f.jdbc.update("UPDATE sv_issuer_store_access SET source_revision=1");
  assertThatThrownBy(()->f.recharge("OLD",10000,2000)).isInstanceOf(BizException.class);
  var live=new ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope("MAIN","M1","S1","B1","CNY",1);
  f.recharge("NEW",10000,2000,live);assertThat(f.jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(12000);
  var brand=new ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyAuthority("MAIN","OWNER","M1","BRAND_STORES","B1",null,"CNY",2);
  f.configs.configure(brand,new PolicyEdit(f.rules(false,1000,100000),0),"BRAND");
  assertThatThrownBy(()->f.recharge("TIGHT",10000,2000,live)).isInstanceOf(BizException.class);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge",Long.class)).isEqualTo(1);
 }
}
