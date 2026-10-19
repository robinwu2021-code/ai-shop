package ai.neargo.shop.pay.points.store;
import java.util.*;
public interface StatementConfirmationStore {
 void publish(String no,long revision,String type,String merchant,String store,String state,long at);
 Map<String,Object> lock(String no,long revision);
 List<Map<String,Object>> pending(String type,int limit);
 List<Map<String,Object>> due(String type,long now,int limit);
 int notified(String no,long revision,long at,long deadline);
 void attempted(String no,long revision,long now);
 int done(String no,long revision);
}
