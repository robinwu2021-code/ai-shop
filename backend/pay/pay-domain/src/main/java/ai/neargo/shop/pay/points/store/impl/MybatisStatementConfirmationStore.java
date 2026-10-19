package ai.neargo.shop.pay.points.store.impl;
import ai.neargo.shop.pay.points.store.StatementConfirmationStore;
import ai.neargo.shop.pay.mapper.points.StatementConfirmationMapper;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class MybatisStatementConfirmationStore implements StatementConfirmationStore {
 private final StatementConfirmationMapper mapper;
 public MybatisStatementConfirmationStore(StatementConfirmationMapper mapper){this.mapper=mapper;}
 public void publish(String no,long revision,String type,String merchant,String store,String state,long at){mapper.supersede(no,revision);mapper.publish(no,revision,type,merchant,store,state,at);}
 public Map<String,Object> lock(String no,long revision){return mapper.lock(no,revision);}
 public List<Map<String,Object>> pending(String type,int limit){return mapper.pending(type,limit);}
 public List<Map<String,Object>> due(String type,long now,int limit){return mapper.due(type,now,limit);}
 public int notified(String no,long revision,long at,long deadline){return mapper.notified(no,revision,at,deadline);}
 public void attempted(String no,long revision,long now){mapper.attempted(no,revision,now);}
 public int done(String no,long revision){return mapper.done(no,revision);}
}
