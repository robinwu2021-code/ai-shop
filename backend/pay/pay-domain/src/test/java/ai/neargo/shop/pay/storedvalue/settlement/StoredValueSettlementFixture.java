package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.pay.storedvalue.settlement.impl.StoredValueSettlementServiceImpl;
import ai.neargo.shop.pay.storedvalue.settlement.impl.StoredValueSettlementPaymentServiceImpl;

import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.*;
import ai.neargo.shop.pay.entitlement.service.*;
import ai.neargo.shop.pay.mapper.entitlement.PayEntCommandDedupMapper;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
/** Real local SQL, MyBatis idempotency, number reservations and Pay transaction manager; no external payment. */
class StoredValueSettlementFixture {
 final DriverManagerDataSource ds=datasource();
 static final java.util.concurrent.atomic.AtomicInteger DATABASE_SEQUENCE=new java.util.concurrent.atomic.AtomicInteger();
 static DriverManagerDataSource datasource(){
  String url=System.getProperty("p6.mysql.url");if(url==null)return new DriverManagerDataSource("jdbc:h2:mem:p6-finance-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/p6_sv_[0-9_]+\\?.*"))throw new IllegalArgumentException("Fresh P6 loopback database required");
  try{String password=Files.readString(Path.of(System.getProperty("p6.mysql.password-file"))).trim();String user=System.getProperty("p6.mysql.user");int slash=url.lastIndexOf('/'),question=url.indexOf('?',slash);String name=url.substring(slash+1,question)+"_"+Long.toUnsignedString(System.nanoTime())+"_"+DATABASE_SEQUENCE.incrementAndGet();
   var admin=new DriverManagerDataSource(url,user,password);new JdbcTemplate(admin).execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");return new DriverManagerDataSource(url.substring(0,slash+1)+name+url.substring(question),user,password);
  }catch(java.io.IOException e){throw new IllegalStateException("Test credential file unavailable",e);}
 }
 final JdbcTemplate jdbc=new JdbcTemplate(ds);final DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);final ObjectMapper json=new ObjectMapper();final IssuerRepository db=new IssuerRepository(manager,json);
 final StoredValueSettlementRepository repo=new StoredValueSettlementRepository(db);final StoredValueCrossEntityReadiness readiness=mock(StoredValueCrossEntityReadiness.class);
 final DocumentNumberCoordinator numbers;final EntitlementCommandDeduplicator commands;final StoredValueSettlementAudit audit;final StoredValueSettlementPaymentService payments;final StoredValueSettlementService bills;
 final StoredValueSettlementEvidenceCipher cipher=new StoredValueSettlementEvidenceCipher(Base64.getEncoder().encodeToString(new byte[32]),"TEST1");
 StoredValueSettlementFixture()throws Exception{
  jdbc.execute("CREATE TABLE sv_issuer(tenant_no VARCHAR(32),issuer_no VARCHAR(64),scope_type VARCHAR(24),scope_no VARCHAR(64),current_issuer_version_no VARCHAR(64),deleted INT DEFAULT 0,PRIMARY KEY(tenant_no,issuer_no))");
  jdbc.execute("CREATE TABLE sv_issuer_version(tenant_no VARCHAR(32),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),settlement_entity_no VARCHAR(64),currency VARCHAR(8),deleted INT DEFAULT 0)");
  jdbc.update("INSERT INTO sv_issuer VALUES('MAIN','I1','BRAND','B1','IV1',0)");jdbc.update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV1','E1','CNY',0)");
  ddl(file("V570__stored_value_clearing_entries.sql"));ddl(file("V575__stored_value_settlement_and_reports.sql"));
  String source=file("V478__entitlement_catalog.sql");int start=source.indexOf("CREATE TABLE IF NOT EXISTS pay_ent_command_dedup");ddl(source.substring(start,source.indexOf(';',start)+1).replace(" COLLATE utf8mb4_bin",""));
  jdbc.execute("CREATE TABLE pay_document_sequence(seq_key VARCHAR(32) PRIMARY KEY,next_value BIGINT NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  jdbc.execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
  var nr=new JdbcDocumentNumberRepository(DocumentOwner.PAY,manager);numbers=new DocumentNumberCoordinator(nr,new SegmentDocumentNumberGenerator(nr,new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),3)),manager);
  var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(PayEntCommandDedupMapper.class);var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);factory.afterPropertiesSet();
  commands=new EntitlementCommandDeduplicator(new SqlSessionTemplate(factory.getObject()).getMapper(PayEntCommandDedupMapper.class),json);audit=new StoredValueSettlementAudit(repo,numbers);payments=new StoredValueSettlementPaymentServiceImpl(repo,audit,cipher,commands,numbers,readiness);bills=new StoredValueSettlementServiceImpl(repo,audit,numbers,commands,readiness);
 }
 static final Set<String> ALL=Set.of("sv.settlement.read","sv.settlement.generate","sv.settlement.reconcile","sv.settlement.dispute","sv.settlement.dispute.resolve","sv.settlement.adjust","sv.settlement.payment.register","sv.settlement.receipt.confirm","sv.settlement.payment.void","sv.settlement.audit");
 Authority actor(String actor,String principal,String entity){return new Authority("MAIN",actor,principal,"M1",List.of(new IssuerGrant("I1","BRAND","B1")),Map.of(entity,ALL),Set.of());}
 Authority payer(){return actor("MCH:PAYER","USER:PAYER","E1");}Authority payee(){return actor("MCH:PAYEE","USER:PAYEE","E2");}
 void bill(long amount){var a=payer();var row=repo.base(a);row.put("bill_no","BILL1");row.put("issuer_no","I1");row.put("payer_entity_no","E1");row.put("payee_entity_no","E2");row.put("currency","CNY");row.put("period_from",java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));row.put("period_to",java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));row.put("bill_kind","CROSS_ENTITY");row.put("status","RECONCILED");row.put("payable_minor",amount);row.put("principal_minor",amount);row.put("bonus_minor",0);row.put("source_count",1);row.put("source_watermark",1);row.put("payer_reconciled_by","A");row.put("payee_reconciled_by","B");row.put("payer_reconciled_at",row.get("created_at"));row.put("payee_reconciled_at",row.get("created_at"));repo.insert("sv_settlement_bill",row);}
 Registration registration(long amount,long revision,String reference,String key){return new Registration(payer(),"BILL1",revision,amount,Instant.parse("2026-10-02T00:00:00Z"),"TRANSFER",reference,List.of(),key);}
 long value(String column){return jdbc.queryForObject("SELECT "+column+" FROM sv_settlement_bill WHERE bill_no='BILL1'",Long.class);}
 void clearing(String no,String payee,long principal,long bonus,long payable,String posted,String occurred){
  var row=repo.base(payer());row.put("clearing_no",no);row.put("issuer_no","I1");row.put("usage_no","U"+no);row.put("allocation_no","A"+no);row.put("lot_no","L"+no);row.put("release_no","RELEASE1");row.put("issuer_version_no","IV1");row.put("recharge_store_no","S1");row.put("redemption_store_no",payee.equals("E1")?"S1":"S2");row.put("payer_entity_no","E1");row.put("payee_entity_no",payee);row.put("currency","CNY");row.put("principal_used_minor",principal);row.put("bonus_used_minor",bonus);row.put("payable_minor",payable);row.put("issuer_borne_minor",bonus);row.put("entry_type",payable<0?"ADJUSTMENT":"USE");row.put("occurred_at",java.sql.Timestamp.from(Instant.parse(occurred)));row.put("created_at",java.sql.Timestamp.from(Instant.parse(posted)));row.put("source_event_key",EntitlementRecords.digest(no));row.put("content_hash",EntitlementRecords.digest("CONTENT:"+no));db.insert("sv_clearing_entry",row);
 }
 String file(String name)throws Exception{return Files.readString(Path.of("../../shop-app/src/main/resources/db/migration",name));}
 void ddl(String source){if(ds.getUrl().startsWith("jdbc:mysql:")){try(var connection=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));return;}catch(java.sql.SQLException error){throw new IllegalStateException("Native fixture DDL failed",error);}}source=source.replaceAll("(?m)^--.*$","").replaceAll(" ENGINE=InnoDB[^;]*","").replace(" JSON"," VARCHAR(30000)").replace(" STORED","");var matcher=java.util.regex.Pattern.compile("([a-z_]+) IN \\(([^()]*)\\)").matcher(source);var out=new StringBuffer();while(matcher.find()){String expr="CASE "+matcher.group(1);for(String value:matcher.group(2).split(","))expr+=" WHEN "+value.trim()+" THEN TRUE";expr+=" ELSE FALSE END";matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(expr));}matcher.appendTail(out);for(String part:out.toString().split(";"))if(!part.isBlank())jdbc.execute(part);}
}
