package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.common.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** All business queries have explicit tenant/resource predicates; no data-scope bypass. */
@Repository
public class ManagementRepository {
 final JdbcTemplate jdbc;
 final TransactionTemplate transactions;
 final ObjectMapper json;
 /** 命令的幂等重放与按幂等键查结果（原 mch_management_command，新库不建；TDD-平台基础库换表 G5） */
 private final ai.neargo.shop.spi.idempotency.RequestIdempotencyPort idempotency;
 /** 管理操作的变更历史写统一审计（G6），品牌「操作记录」页按审计对象读回 */
 private final ai.neargo.shop.spi.platform.AuditLogPort audit;
 /** 命令在统一防重里的入口名 */
 static final String JOURNAL_ENDPOINT="management.command";
 public ManagementRepository(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc,@Qualifier(ModuleDataSources.PLATFORM_TX_MANAGER) PlatformTransactionManager tx,ObjectMapper json,
   ai.neargo.shop.spi.idempotency.RequestIdempotencyPort idempotency,ai.neargo.shop.spi.platform.AuditLogPort audit) {
  this.jdbc=jdbc;this.transactions=new TransactionTemplate(tx);this.json=json;this.idempotency=idempotency;this.audit=audit;
 }
 /** 统一审计（Spring 代理下不能直接读字段） */
 public ai.neargo.shop.spi.platform.AuditLogPort audit(){return audit;}
 /** 审计对象：某集团下某个目标（集团、品牌、门店……）的管理操作 */
 public static String auditTarget(String tenantNo,String target){return "MANAGEMENT:"+tenantNo+"|"+target;}
 public <T> T write(Supplier<T> action) {
  try{return transactions.execute(s->action.get());}
  catch(org.springframework.dao.DuplicateKeyException|org.springframework.dao.CannotAcquireLockException e){throw BizException.of(ErrorCode.CONFLICT);}
 }
 public Map<String,Object> one(String sql,Object...args) { var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst(); }
 public List<Map<String,Object>> rows(String sql,Object...args){return jdbc.queryForList(sql,args);}
 public int update(String sql,Object...args){return jdbc.update(sql,args);}
 public void insert(String table,Map<String,Object> values){
  var cols=String.join(",",values.keySet());var marks=String.join(",",Collections.nCopies(values.size(),"?"));
  jdbc.update("INSERT INTO "+table+" ("+cols+") VALUES ("+marks+")",values.values().toArray());
 }
 public Map<String,Object> base(ManagementModels.Actor a){
  var v=new LinkedHashMap<String,Object>();v.put("tenant_no",a.tenantNo());v.put("version",0);v.put("deleted",0);
  var at=Timestamp.from(Instant.now());v.put("created_at",at);v.put("updated_at",at);
  v.put("created_by",a.actorKey());v.put("updated_by",a.actorKey());return v;
 }
 public static String str(Map<String,Object> r,String k){return r==null||r.get(k)==null?null:r.get(k).toString();}
 public static long num(Map<String,Object> r,String k){return r==null||r.get(k)==null?0:((Number)r.get(k)).longValue();}
 public static Instant instant(Object value){
  if(value==null)return null;
  if(value instanceof Timestamp timestamp)return timestamp.toInstant();
  if(value instanceof LocalDateTime time)return time.toInstant(ZoneOffset.UTC);
  throw new IllegalStateException("Unsupported database datetime");
 }
 public static boolean bool(Map<String,Object> r,String k){return r!=null&&(Boolean.TRUE.equals(r.get(k))||"1".equals(str(r,k)));}
 public Map<String,Object> view(Map<String,Object> row){
  if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
  var result=new LinkedHashMap<String,Object>();
  Set<String> hidden=Set.of("id","tenant_no","deleted","authority_snapshot","check_snapshot","authorization_snapshot","bootstrap_hash","create_hash","command_hash","decision_hash","bootstrap_key","create_key","command_key","decision_key","active_store_no","pending_store_no","name_key");
  row.forEach((key,value)->{if(!hidden.contains(key)){
   var b=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()){if(c=='_'){upper=true;continue;}b.append(upper?Character.toUpperCase(c):c);upper=false;}
   String name=b.toString();if("version".equals(key))name="revision";
   result.put(name,value instanceof Timestamp ts?ts.toInstant().toString():value instanceof java.time.LocalDateTime dt?dt.toInstant(ZoneOffset.UTC).toString():value);
  }});
  for(String field:List.of("created_by","updated_by"))if(row.containsKey(field)){
   String key=str(row,field);Map<String,Object> actor=null;
   if(key!=null&&key.startsWith("MCH:"))actor=one("SELECT display_name FROM mch_account WHERE tenant_no=? AND mch_account_no=? AND deleted=0",str(row,"tenant_no"),key.substring(4));
   if(key!=null&&key.startsWith("USER:"))actor=one("SELECT display_name FROM mch_account WHERE tenant_no=? AND user_no=? AND deleted=0 ORDER BY mch_account_no LIMIT 1",str(row,"tenant_no"),key.substring(5));
   result.put(field.equals("created_by")?"createdByLabel":"updatedByLabel",actor==null||str(actor,"display_name")==null?"—":str(actor,"display_name"));
  }
  if(row.containsKey("grant_no")){
   String type=str(row,"resource_type"),no=str(row,"resource_no"),tenant=str(row,"tenant_no");
   String table=switch(type){case "SPACE"->"mch_group";case "BRAND"->"mch_brand";case "STORE"->"mch_store";case "ENTITY"->"mch_entity";default->null;};
   String column=switch(type){case "SPACE"->"management_no";case "BRAND"->"brand_no";case "STORE"->"store_no";case "ENTITY"->"entity_no";default->null;};
   var resource=table==null?null:one("SELECT name FROM "+table+" WHERE tenant_no=? AND "+column+"=? AND deleted=0",tenant,no);
   result.put("resourceName",resource==null?type:str(resource,"name"));
   String actorKey=str(row,"actor_no");var actor=one("SELECT display_name FROM mch_account WHERE tenant_no=? AND "+(actorKey.startsWith("MCH:")?"mch_account_no":"user_no")+"=? AND deleted=0 ORDER BY mch_account_no LIMIT 1",tenant,actorKey.substring(actorKey.indexOf(':')+1));
   result.put("actorLabel",actor==null||str(actor,"display_name")==null?"—":str(actor,"display_name"));
  }
  return result;
 }
 public List<String> stringList(String value){return json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,String.class));}
 public String encode(Object value){return json.writeValueAsString(value);}
 public <T>T decode(String value,Class<T> type){return json.readValue(value,type);}
 @SuppressWarnings("unchecked") public Map<String,Object> decode(Object value){
  if(value instanceof Map<?,?> map)return (Map<String,Object>)map;
  if(value instanceof byte[] bytes)value=new String(bytes,StandardCharsets.UTF_8);
  if(value instanceof java.sql.Clob clob)try{value=clob.getSubString(1,(int)clob.length());}catch(SQLException e){throw new IllegalStateException(e);}
  Object parsed=json.readValue(value.toString(),Object.class);
  if(parsed instanceof String text)parsed=json.readValue(text,Object.class); // H2 JSON stores bound strings as JSON strings.
  return (Map<String,Object>)parsed;
 }
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public String commandId(ManagementModels.Actor a,String target,String action,String key){return hash(a.tenantNo()+"|"+a.actorKey()+"|"+target+"|"+action+"|"+ManagementModels.text(key,64));}
 public Map<String,Object> replay(String id,String commandHash){
  @SuppressWarnings("unchecked") Map<String,Object> payload=idempotency.findCompleted(id,JOURNAL_ENDPOINT,null,null,Map.class).orElse(null);
  if(payload==null)return null;
  // 命令摘要放在载荷里自己比：按幂等键查结果（command）时并不知道摘要
  if(!Objects.equals(commandHash,payload.get("commandHash")))throw BizException.of(ErrorCode.CONFLICT);
  return decode(encode(payload.get("resultSnapshot")));
 }
 public Map<String,Object> command(ManagementModels.Actor a,String target,String action,String key){
  @SuppressWarnings("unchecked") Map<String,Object> payload=idempotency.findCompleted(commandId(a,target,action,key),JOURNAL_ENDPOINT,null,null,Map.class).orElse(null);
  if(payload==null||!a.tenantNo().equals(payload.get("tenantNo")))throw BizException.of(ErrorCode.NOT_FOUND);
  return decode(encode(payload.get("resultSnapshot")));
 }
 /**
  * 记下一条已完成的命令：进统一防重（重放用）并写统一审计（变更历史）。
  * 平台事务提交之后才记 —— 事务回滚的写不能留下可重放的结果；提交后、记之前进程没了，重试由业务表唯一键兜住（G5）。
  */
 public void emit(ManagementModels.Actor a,String id,String target,String action,String commandHash,Map<String,Object> result){
  var payload=new LinkedHashMap<String,Object>();payload.put("tenantNo",a.tenantNo());payload.put("actorKey",a.actorKey());
  payload.put("action",action);payload.put("target",target);payload.put("commandHash",commandHash);payload.put("resultSnapshot",result);
  // 统一审计自己就等提交后再写（回滚不留痕）；放进下面的 afterCommit 里再登记一次反而会被丢掉
  audit.record(action,auditTarget(a.tenantNo(),target),a.actorKey(),false,null,encode(payload));
  Runnable record=()->idempotency.execute(id,JOURNAL_ENDPOINT,null,null,Map.class,()->payload);
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()){
   org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
    @Override public void afterCommit(){record.run();}
   });
  }else record.run();
 }
}
