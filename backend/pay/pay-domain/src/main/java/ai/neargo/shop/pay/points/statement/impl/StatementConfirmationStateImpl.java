package ai.neargo.shop.pay.points.statement.impl;
import ai.neargo.shop.pay.points.statement.*;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.model.PositionMove;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
@Service
@Transactional("payTxManager")
public class StatementConfirmationStateImpl implements StatementConfirmationState {
 private final StatementConfirmationStore metadata;private final PointsReadStore read;private final PointsConfigurationStore config;private final StatementStore statements;private final ObjectProvider<StoreStatementService> service;
 public StatementConfirmationStateImpl(StatementConfirmationStore metadata,PointsReadStore read,PointsConfigurationStore config,StatementStore statements,ObjectProvider<StoreStatementService> service){this.metadata=metadata;this.read=read;this.config=config;this.statements=statements;this.service=service;}
 private StoreStatementService.Snapshot current(String no){var first=read.position(PositionMove.Kind.STATEMENT.table(),no);if(first==null)return null;config.lockTypeAndPool((String)first.get("type_code"));var head=statements.lock(no);return new ObjectMapper().readValue((String)head.get("payload_json"),StoreStatementService.Snapshot.class);}
 public void notified(String no,long revision,long at,long deadline){var s=current(no);if(s==null || s.current().number()!=revision || !"DRAFT".equals(s.state()))return;metadata.lock(no,revision);metadata.notified(no,revision,at,deadline);}
 public boolean confirm(String no,long revision,long now){
  var s=current(no);if(s==null || s.current().number()!=revision || !"DRAFT".equals(s.state()))return false;
  var row=metadata.lock(no,revision);if(row==null || !"DRAFT".equals(row.get("state")) || row.get("notified_at")==null || row.get("deadline")==null || ((Number)row.get("deadline")).longValue()>now)return false;
  service.getObject().confirm(no,revision,"SYSTEM:STATEMENT_AUTO_CONFIRM");metadata.done(no,revision);return true;
 }
}
