package ai.neargo.shop.promotion.service;
import ai.neargo.shop.spi.storedvalue.StoredValueActivityFinancePort;

import ai.neargo.shop.promotion.service.impl.StoredValueActivityServiceImpl;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.VersionView;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class StoredValuePublishRecoveryTest {
 @Test void lostPayActivationAcknowledgementResumesSameReleaseAndPublishedVersion()throws Exception{
  var f=new StoredValueGovernanceFixture();var activities=mock(ActivityVersionService.class);var finance=mock(StoredValueActivityFinancePort.class);var policy=f.policy(false,"b".repeat(64));
  when(activities.get(any(),eq("A1"))).thenAnswer(i->f.view());
  var immutableVersion=new AtomicReference<VersionView>();var versionWrites=new AtomicInteger();
  when(activities.publish(any(),eq("A1"),eq(0L),anyString())).thenAnswer(i->{if(immutableVersion.get()==null){versionWrites.incrementAndGet();f.jdbc.update("UPDATE pmt_activity SET version=1,status='RUNNING'");immutableVersion.set(new VersionView("A1","AV1",1,"c".repeat(64),Instant.now(),f.definition));}return immutableVersion.get();});
  var tiers=List.of(new Tier("T1",10000,2000));
  when(finance.check(any())).thenReturn(new Check(policy,true,false,List.of()));
  when(finance.prepare(any())).thenReturn(new View("R1","I1","PREPARED","d".repeat(64),policy.hash(),false,null,tiers));
  var activated=new AtomicBoolean();var committed=new AtomicInteger();
  when(finance.activate(any())).thenAnswer(i->{if(activated.compareAndSet(false,true)){committed.incrementAndGet();throw new IllegalStateException("Simulated lost acknowledgement");}return new View("R1","I1","ACTIVE","d".repeat(64),policy.hash(),false,"AV1",tiers);});
  var service=new StoredValueActivityServiceImpl(activities,f.journal,finance);
  assertThatThrownBy(()->service.submit(f.submitter,"A1",0,"SUBMIT")).isInstanceOf(IllegalStateException.class);
  assertThat(f.journal.find(f.submitter,"A1",0).state()).isEqualTo("PAY_READY");
  var result=service.submit(f.submitter,"A1",0,"SUBMIT");
  assertThat(result.state()).isEqualTo("ACTIVE");assertThat(result.releaseNo()).isEqualTo("R1");assertThat(versionWrites.get()).isEqualTo(1);assertThat(committed.get()).isEqualTo(1);verify(finance,times(1)).prepare(any());
 }
 @Test void delayedActivationCannotOverrideDurableStop()throws Exception{
  var f=new StoredValueGovernanceFixture();var s=f.journal.register(f.submitter,f.view(),f.policy(false,"b".repeat(64)),"SUBMIT");var tiers=List.of(new Tier("T1",10000,2000));
  f.journal.prepared(f.submitter,s.linkNo(),new View("R1","I1","PREPARED","d".repeat(64),s.policyHash(),false,null,tiers));
  f.journal.state(f.submitter,s.linkNo(),"STOP_REQUESTED");f.journal.state(f.submitter,s.linkNo(),"STOPPED");
  var late=f.journal.activated(f.submitter,s.linkNo(),"AV1",new View("R1","I1","ACTIVE","d".repeat(64),s.policyHash(),false,"AV1",tiers));assertThat(late.state()).isEqualTo("STOPPED");
 }
}
