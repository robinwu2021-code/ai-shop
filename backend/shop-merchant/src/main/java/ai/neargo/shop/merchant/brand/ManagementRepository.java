package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.common.*;
import ai.neargo.shop.event.outbox.*;
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
 final OutboxIngress events;
 public ManagementRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,
        @Qualifier("managementOutboxIngress") OutboxIngress events) {
  this.jdbc=jdbc;this.transactions=new TransactionTemplate(tx);this.json=json;this.events=events;
 }
 public <T> T write(Supplier<T> action) {
  try{return transactions.execute(s->action.get());}
  catch(org.springframework.dao.DuplicateKeyException|org.springframework.dao.CannotAcquireLockException e){throw BizException.of(ErrorCode.CONFLICT);}
 }
 Map<String,Object> one(String sql,Object...args) { var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst(); }
 List<Map<String,Object>> rows(String sql,Object...args){return jdbc.queryForList(sql,args);}
 int update(String sql,Object...args){return jdbc.update(sql,args);}
 void insert(String table,Map<String,Object> values){
  var cols=String.join(",",values.keySet());var marks=String.join(",",Collections.nCopies(values.size(),"?"));
  jdbc.update("INSERT INTO "+table+" ("+cols+") VALUES ("+marks+")",values.values().toArray());
 }
 Map<String,Object> base(ManagementModels.Actor a){
  var v=new LinkedHashMap<String,Object>();v.put("tenant_no",a.tenantNo());v.put("version",0);v.put("deleted",0);
  var at=Timestamp.from(Instant.now());v.put("created_at",at);v.put("updated_at",at);
  v.put("created_by",a.actorKey());v.put("updated_by",a.actorKey());return v;
 }
 static String str(Map<String,Object> r,String k){return r==null||r.get(k)==null?null:r.get(k).toString();}
 static long num(Map<String,Object> r,String k){return r==null||r.get(k)==null?0:((Number)r.get(k)).longValue();}
 static boolean bool(Map<String,Object> r,String k){return r!=null&&(Boolean.TRUE.equals(r.get(k))||"1".equals(str(r,k)));}
 Map<String,Object> view(Map<String,Object> row){
  if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
  var result=new LinkedHashMap<String,Object>();
  Set<String> hidden=Set.of("id","tenant_no","deleted","authority_snapshot","check_snapshot","authorization_snapshot","bootstrap_hash","create_hash","command_hash","decision_hash","bootstrap_key","create_key","command_key","decision_key","active_store_no","pending_store_no","name_key");
  row.forEach((key,value)->{if(!hidden.contains(key)){
   var b=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()){if(c=='_'){upper=true;continue;}b.append(upper?Character.toUpperCase(c):c);upper=false;}
   String name=b.toString();if("version".equals(key))name="revision";
   result.put(name,value instanceof Timestamp ts?ts.toInstant().toString():value instanceof java.time.LocalDateTime dt?dt.toInstant(ZoneOffset.UTC).toString():value);
  }});return result;
 }
 String encode(Object value){return json.writeValueAsString(value);}
 @SuppressWarnings("unchecked") Map<String,Object> decode(Object value){
  if(value instanceof byte[] bytes)value=new String(bytes,StandardCharsets.UTF_8);
  if(value instanceof java.sql.Clob clob)try{value=clob.getSubString(1,(int)clob.length());}catch(SQLException e){throw new IllegalStateException(e);}
  Object parsed=json.readValue(value.toString(),Object.class);
  if(parsed instanceof String text)parsed=json.readValue(text,Object.class); // H2 JSON stores bound strings as JSON strings.
  return (Map<String,Object>)parsed;
 }
 static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 String commandId(ManagementModels.Actor a,String target,String action,String key){return hash(a.tenantNo()+"|"+a.actorKey()+"|"+target+"|"+action+"|"+ManagementModels.text(key,64));}
 Map<String,Object> replay(String id,String commandHash){
  var row=one("SELECT payload FROM sys_outbox WHERE source_system='PLATFORM' AND source_event_no=?",id);
  if(row==null)return null;var payload=decode(row.get("payload"));
  if(!Objects.equals(commandHash,payload.get("commandHash")))throw BizException.of(ErrorCode.CONFLICT);
  return decode(encode(payload.get("resultSnapshot")));
 }
 void emit(ManagementModels.Actor a,String id,String target,String action,String commandHash,Map<String,Object> result){
  var payload=new LinkedHashMap<String,Object>();payload.put("tenantNo",a.tenantNo());payload.put("actorKey",a.actorKey());
  payload.put("action",action);payload.put("target",target);payload.put("commandHash",commandHash);payload.put("resultSnapshot",result);
  events.accept(new OutboxEnvelope(BizKey.next(BizKey.EVENT),"PLATFORM",id,"MANAGEMENT",target,"MANAGEMENT_CHANGED",1,"MANAGEMENT:"+a.tenantNo()+":"+target,0,encode(payload),Instant.now()));
 }
}
