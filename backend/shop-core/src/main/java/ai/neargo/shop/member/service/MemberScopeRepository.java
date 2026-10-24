package ai.neargo.shop.member.service;
import ai.neargo.shop.common.*;import org.springframework.stereotype.Repository;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.beans.factory.annotation.Qualifier;import tools.jackson.databind.ObjectMapper;
import java.util.*;import java.time.*;import java.sql.Timestamp;import java.util.function.Supplier;
@Repository
public class MemberScopeRepository {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
 public MemberScopeRepository(JdbcTemplate jdbc,@Qualifier("transactionManager") PlatformTransactionManager manager,ObjectMapper json){this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.json=json;}
 public <T>T write(Supplier<T> action){boolean joined=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();for(int i=0;;i++)try{return tx.execute(status->action.get());}catch(org.springframework.dao.DuplicateKeyException|org.springframework.dao.CannotAcquireLockException e){if(joined||i>=2)throw BizException.of(ErrorCode.CONFLICT);}}
 public Map<String,Object> one(String sql,Object...args){var list=rows(sql,args);return list.isEmpty()?null:list.getFirst();}
 public List<Map<String,Object>> rows(String sql,Object...args){return jdbc.queryForList(sql,args);}
 public int update(String sql,Object...args){return jdbc.update(sql,args);}
 public void insert(String table,Map<String,Object> row){jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
 public Map<String,Object> base(String tenant,String actor){var at=Timestamp.from(Instant.now());var row=new LinkedHashMap<String,Object>();row.put("tenant_no",tenant);row.put("version",0);row.put("deleted",0);row.put("created_at",at);row.put("updated_at",at);row.put("created_by",actor);row.put("updated_by",actor);return row;}
 public String encode(Object v){return json.writeValueAsString(v);}
 @SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return json.readValue(value,Map.class);}
 public static String s(Map<String,Object> row,String key){return row==null||row.get(key)==null?null:row.get(key).toString();}
 public static long n(Map<String,Object> row,String key){return row==null||row.get(key)==null?0:((Number)row.get(key)).longValue();}
 public MemberScopeModels.ScopeMember member(Map<String,Object> row){if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return new MemberScopeModels.ScopeMember(s(row,"scope_member_no"),s(row,"customer_no"),s(row,"scope_type"),s(row,"scope_no"),s(row,"first_store_no"),s(row,"status"));}
}
