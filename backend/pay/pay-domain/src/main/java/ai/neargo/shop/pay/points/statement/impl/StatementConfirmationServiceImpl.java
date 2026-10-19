package ai.neargo.shop.pay.points.statement.impl;
import ai.neargo.shop.pay.points.statement.StatementConfirmationState;
import ai.neargo.shop.pay.points.store.StatementConfirmationStore;
import ai.neargo.shop.spi.points.StatementNoticePort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Map;
@Service
public class StatementConfirmationServiceImpl {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StatementConfirmationServiceImpl.class);
 public record Result(int processed,int deferred){}
 private java.time.Clock clock;
 @Autowired void installClock(ObjectProvider<java.time.Clock> clocks){this.clock=clocks.getIfAvailable(java.time.Clock::systemUTC);}
 private final StatementConfirmationStore store;private final StatementNoticePort notices;private final StatementConfirmationState state;private final int days;
 public StatementConfirmationServiceImpl(StatementConfirmationStore store,StatementNoticePort notices,StatementConfirmationState state,int days){if(days<1 || days>90)throw new IllegalArgumentException("POINTS_CONFIRM_DAYS_INVALID");this.store=store;this.notices=notices;this.state=state;this.days=days;}
 @Autowired public StatementConfirmationServiceImpl(StatementConfirmationStore store,ObjectProvider<StatementNoticePort> notices,StatementConfirmationState state,@Value("${shop.points.statement-confirm-days:7}")int days){this(store,notices.getIfAvailable(()->n->false),state,days);}
 public Result run(String type,int limit,long now){
  if(type==null || type.isBlank() || limit<1 || limit>200 || now<0)throw new IllegalArgumentException("POINTS_CONFIRM_INPUT_INVALID");
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("POINTS_CONFIRM_REQUIRES_NO_TRANSACTION");
  int processed=0,deferred=0;
  for(var row:store.pending(type,limit))try{
   String no=(String)row.get("statement_no");long rev=num(row,"revision");
   store.attempted(no,rev,now);
   boolean delivered=notices.deliver(new StatementNoticePort.Notice(no,rev,type,(String)row.get("merchant_no"),(String)row.get("store_no"),days));
   if(delivered){long notifiedAt=clock==null?now:Math.max(now,clock.millis());state.notified(no,rev,notifiedAt,Math.addExact(notifiedAt,Math.multiplyExact((long)days,86400000L)));}else deferred++;
  }catch(RuntimeException failure){deferred++;log.warn("积分账单通知待重试 statement={} revision={} reason={}",row.get("statement_no"),row.get("revision"),failure.getMessage());}
  for(var row:store.due(type,now,limit))try{store.attempted((String)row.get("statement_no"),num(row,"revision"),now);if(state.confirm((String)row.get("statement_no"),num(row,"revision"),now))processed++;else deferred++;}catch(RuntimeException failure){deferred++;log.warn("积分账单自动确认待复核 statement={} revision={} reason={}",row.get("statement_no"),row.get("revision"),failure.getMessage());}
  return new Result(processed,deferred);
 }
 private long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
