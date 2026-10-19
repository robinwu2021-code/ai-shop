package ai.neargo.shop.scenario;
import ai.neargo.shop.pay.points.statement.impl.StatementConfirmationServiceImpl;
import ai.neargo.shop.pay.points.statement.StatementConfirmationState;
import ai.neargo.shop.pay.points.store.StatementConfirmationStore;
import ai.neargo.shop.spi.points.StatementNoticePort;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PointsStatementConfirmationTest {
 @Test void notification_failure_does_not_start_deadline(){
  var store=mock(StatementConfirmationStore.class);var notice=mock(StatementNoticePort.class);var state=mock(StatementConfirmationState.class);
  when(store.pending("T",10)).thenReturn(List.of(Map.of("statement_no","S","revision",1L,"type_code","T","store_no","ST","merchant_no","M")));
  when(notice.deliver(any())).thenReturn(false);when(store.due(anyString(),anyLong(),anyInt())).thenReturn(List.of());
  var result=new StatementConfirmationServiceImpl(store,notice,state,7).run("T",10,1000);
  assertThat(result.deferred()).isEqualTo(1);verify(state,never()).notified(anyString(),anyLong(),anyLong(),anyLong());
 }
 @Test void successful_notification_gets_full_window(){
  var store=mock(StatementConfirmationStore.class);var notice=mock(StatementNoticePort.class);var state=mock(StatementConfirmationState.class);
  when(store.pending("T",10)).thenReturn(List.of(Map.of("statement_no","S","revision",1L,"type_code","T","store_no","ST","merchant_no","M")));
  when(notice.deliver(any())).thenReturn(true);when(store.due(anyString(),anyLong(),anyInt())).thenReturn(List.of());
  new StatementConfirmationServiceImpl(store,notice,state,7).run("T",10,1000);
  verify(state).notified("S",1,1000,604801000);
 }
 @Test void each_due_record_is_bounded_and_confirmed_independently(){
  var store=mock(StatementConfirmationStore.class);var notice=mock(StatementNoticePort.class);var state=mock(StatementConfirmationState.class);
  when(store.pending("T",2)).thenReturn(List.of());when(store.due("T",1000,2)).thenReturn(List.of(Map.of("statement_no","A","revision",1L),Map.of("statement_no","B","revision",2L)));
  when(state.confirm("A",1,1000)).thenThrow(new IllegalArgumentException("DISPUTED"));when(state.confirm("B",2,1000)).thenReturn(true);
  var result=new StatementConfirmationServiceImpl(store,notice,state,7).run("T",2,1000);
  assertThat(result.processed()).isEqualTo(1);assertThat(result.deferred()).isEqualTo(1);
 }
}
