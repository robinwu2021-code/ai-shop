package ai.neargo.shop.pay.points.statement;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;
@Service
public class StatementConfirmationPublisher {
 @org.springframework.beans.factory.annotation.Autowired
 private ObjectProvider<StatementBuildStore> builds;
 private final StatementConfirmationStore store; private final PointsReadStore read; private final Clock clock;
 public StatementConfirmationPublisher(StatementConfirmationStore store,PointsReadStore read,ObjectProvider<Clock> clocks){this.store=store;this.read=read;this.clock=clocks.getIfAvailable(Clock::systemUTC);}
 public void publish(StoreStatementService.Snapshot s){
  String merchant="";
  if(!s.positionIds().isEmpty()){
   var source=read.position(PositionMove.Kind.REDEEM.table(),s.positionIds().getFirst());
   merchant=new ObjectMapper().readValue((String)source.get("payload_json"),RedeemSnapshot.class).share().merchantNo();
  } else if(s.sourceBuildNo()!=null && builds!=null && builds.getIfAvailable()!=null) {
   var source=builds.getObject().firstSource(s.statementNo());
   if(source!=null)merchant=new ObjectMapper().readValue((String)source.get("payload_json"),RedeemSnapshot.class).share().merchantNo();
  }
  store.publish(s.statementNo(),s.current().number(),s.period().typeCode(),merchant,s.period().storeNo(),s.state(),clock.millis());
 }
}
