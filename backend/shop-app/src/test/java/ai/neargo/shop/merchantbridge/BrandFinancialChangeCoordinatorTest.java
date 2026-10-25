package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.merchant.brand.BrandFinancialChangeService;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BrandFinancialChangeCoordinatorTest {
 final Actor actor=new Actor("MAIN","MCH:OWNER");
 final Prepare command=new Prepare("MAIN",actor.actorKey(),"M1","CHANGE1","S1","E1",List.of(new Side("I1","IV1","B1","JOIN",null,"BIND1",-1,1)),2);
 final String hash="a".repeat(64);final Ack prepared=new Ack("CHANGE1",hash,"PREPARED",List.of("I1")),committed=new Ack("CHANGE1",hash,"ACTIVE",List.of("I1"));
 @Test void lostPrepareAcknowledgementRetriesTheExactOriginalCommand() {
  var journal=mock(BrandFinancialChangeService.class);var pay=mock(StoredValueFinanceAppService.class);var before=new BrandFinancialChangeService.Pending("CHANGE1","PREPARING",false,command);var staged=new BrandFinancialChangeService.Pending("CHANGE1","PAY_PREPARED",true,command);var done=new BrandFinancialChangeService.Pending("CHANGE1","COMPLETED",true,command);
  when(journal.original(actor,"M1","CHANGE1")).thenReturn(before);when(pay.prepareMembership(command)).thenThrow(new IllegalStateException("acknowledgement lost")).thenReturn(prepared);when(journal.prepared(actor,"M1","CHANGE1",prepared)).thenReturn(staged);when(journal.stage(actor,"M1","CHANGE1")).thenReturn(staged);when(journal.expectedPayHash(command)).thenReturn(hash);when(pay.commitMembership(new Commit(command,hash,"CHANGE1"))).thenReturn(committed);when(journal.complete(actor,"M1","CHANGE1",committed)).thenReturn(done);
  var service=new BrandFinancialChangeCoordinator(journal,pay);assertThrows(IllegalStateException.class,()->service.resume(actor,"M1","CHANGE1"));verify(journal,never()).stage(any(),anyString(),anyString());assertEquals("COMPLETED",service.resume(actor,"M1","CHANGE1").state());verify(pay,times(2)).prepareMembership(command);verify(journal,times(1)).complete(actor,"M1","CHANGE1",committed);
 }
 @Test void lostCommitAcknowledgementResumesStagedOrganizationWithoutPreparingAnotherChange(){
  var journal=mock(BrandFinancialChangeService.class);var pay=mock(StoredValueFinanceAppService.class);var staged=new BrandFinancialChangeService.Pending("CHANGE1","PAY_PREPARED",true,command);var done=new BrandFinancialChangeService.Pending("CHANGE1","COMPLETED",true,command);var state=new AtomicReference<>(staged);
  when(journal.original(actor,"M1","CHANGE1")).thenAnswer(inv->state.get());when(journal.stage(actor,"M1","CHANGE1")).thenReturn(staged);when(journal.expectedPayHash(command)).thenReturn(hash);when(pay.commitMembership(new Commit(command,hash,"CHANGE1"))).thenThrow(new IllegalStateException("commit acknowledgement lost")).thenReturn(committed);when(journal.complete(actor,"M1","CHANGE1",committed)).thenAnswer(inv->{state.set(done);return done;});var service=new BrandFinancialChangeCoordinator(journal,pay);
  assertThrows(IllegalStateException.class,()->service.resume(actor,"M1","CHANGE1"));assertEquals("COMPLETED",service.resume(actor,"M1","CHANGE1").state());assertEquals("COMPLETED",service.resume(actor,"M1","CHANGE1").state());verify(pay,times(2)).commitMembership(new Commit(command,hash,"CHANGE1"));verify(pay,never()).prepareMembership(any());verify(journal,times(1)).complete(actor,"M1","CHANGE1",committed);
 }
}
