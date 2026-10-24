package ai.neargo.shop.promotion.service;
import ai.neargo.shop.promotion.dto.*;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.*;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
class StoredValueGovernanceFixture {
 final DriverManagerDataSource ds=dataSource();final boolean nativeMysql=System.getProperty("p4.core.mysql.url")!=null;
 static DriverManagerDataSource dataSource(){String url=System.getProperty("p4.core.mysql.url");if(url==null)return new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/p4_sv_[0-9_]+\\?.*"))throw new IllegalArgumentException("Isolated P4 Core MySQL database required");try{return new DriverManagerDataSource(url,System.getProperty("p4.mysql.user"),Files.readString(Path.of(System.getProperty("p4.mysql.password-file"))).trim());}catch(java.io.IOException e){throw new IllegalStateException("Missing isolated credentials");}}
 final JdbcTemplate jdbc=new JdbcTemplate(ds);final ObjectMapper json=new ObjectMapper();final StoredValueActivityJournal journal=new StoredValueActivityJournal(new DataSourceTransactionManager(ds),json);
 final StoreScope policyScope=new StoreScope("MAIN","M1","S1",null,"CNY");
 final Scope submitter=new Scope(policyScope,"E1","MCH:MA1","USER:P1",1),approver=new Scope(policyScope,"E1","MCH:MA2","USER:P2",1);
 final UnifiedActivityDefinition definition;
 StoredValueGovernanceFixture()throws Exception{
  if(nativeMysql)for(String table:List.of("pmt_sv_approval","pmt_sv_release_link","pmt_activity"))jdbc.execute("DROP TABLE IF EXISTS "+table);
  jdbc.execute("CREATE TABLE pmt_activity(tenant_no VARCHAR(32),entity_no VARCHAR(64),activity_no VARCHAR(64) PRIMARY KEY,version BIGINT,status VARCHAR(24),draft_snapshot "+(nativeMysql?"JSON":"VARCHAR(20000)")+",deleted INT DEFAULT 0)");
  var ddl=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V565__stored_value_activity_governance.sql"));ddl=ddl.substring(ddl.indexOf("CREATE TABLE pmt_sv_release_link"));if(!nativeMysql)ddl=h2Checks(ddl).replace(" JSON"," VARCHAR(20000)").replaceAll(" ENGINE=InnoDB[^;]*","");for(String sql:ddl.split(";"))if(!sql.isBlank())jdbc.execute(sql);
  var scope=new RedemptionScope("STORES",List.of("S1"));
  definition=new UnifiedActivityDefinition("Offer","RECHARGE","AP008",1,scope,null,new UnifiedActivityDefinition.Schedule("ONE_OFF",Instant.now().minusSeconds(60),Instant.now().plusSeconds(86400),"Asia/Shanghai",List.of(),null,null),new UnifiedActivityDefinition.Rule(1,new UnifiedActivityDefinition.Trigger("NONE",null,null),List.of(),List.of(new UnifiedActivityDefinition.Tier("T1",10000,2000,scope))),null,null,10,"CNY");
  jdbc.update("INSERT INTO pmt_activity VALUES('MAIN','E1','A1',0,'DRAFT',?,0)",json.writeValueAsString(definition));
 }
 ActivityView view(){var row=jdbc.queryForMap("SELECT version,status,draft_snapshot FROM pmt_activity WHERE activity_no='A1'");return new ActivityView("A1",((Number)row.get("version")).longValue(),row.get("status").toString(),"RUNNING".equals(row.get("status"))?"AV1":null,json.readValue(row.get("draft_snapshot").toString(),UnifiedActivityDefinition.class));}
 EffectivePolicy policy(boolean approval,String hash){var rules=new Rules(true,approval,2000,100000,100000,365,1000000);return new EffectivePolicy(policyScope,rules,List.of(new PolicyVersion("MAIN","M1","P1","PV1","SPACE","M1","CNY",rules)),hash,true);}
 private static String h2Checks(String ddl){var matcher=java.util.regex.Pattern.compile("([a-z_]+) IN \\(([^()]*)\\)").matcher(ddl);var out=new StringBuffer();while(matcher.find()){var expression=new StringBuilder("(CASE ").append(matcher.group(1));for(String item:matcher.group(2).split(","))expression.append(" WHEN ").append(item.trim()).append(" THEN TRUE");expression.append(" ELSE FALSE END)");matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(expression.toString()));}matcher.appendTail(out);return out.toString();}
}
