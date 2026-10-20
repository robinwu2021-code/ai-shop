package ai.neargo.shop.pay.entitlement.service;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.JdbcDocumentNumberRepository;
import ai.neargo.shop.pay.storedvalue.entity.SvStatement;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValueMerchantQueryServiceImpl;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.mapper.storedvalue.*;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueRecordAliasLookupTest {
 @Test void resolvesAllThreeAliasesThroughRealMappingAndScopedSql() {
  var f=new Fixture();
  for(String alias:List.of("PR20261020000001","EU20261020000001","SV20261020000001","SV20261020000002")) {
   var result=f.service.record(f.scope,"STORE",alias);
   assertEquals(alias.startsWith("PR") || alias.endsWith("000001") && alias.startsWith("SV") ? "voucher-r" : "voucher-c",result.recordNo());
  }
  assertEquals("voucher-r",f.service.record(f.scope,"STORE","voucher-r").recordNo());
 }
 @Test void mappedAliasesCannotCrossEntityStoreOrTenantAndUnknownAliasDoesNotFallBack() {
  var f=new Fixture();
  for(String alias:List.of("PR20261020000001","EU20261020000001","SV20261020000001")) {
   assertThrows(ai.neargo.shop.common.BizException.class,()->f.service.record(new CatalogScope("MAIN","OTHER","ACTOR",false),"STORE",alias));
   assertThrows(ai.neargo.shop.common.BizException.class,()->f.service.record(f.scope,"OTHER",alias));
   assertThrows(ai.neargo.shop.common.BizException.class,()->f.service.record(new CatalogScope("OTHER","ENTITY","ACTOR",false),"STORE",alias));
  }
  assertThrows(ai.neargo.shop.common.BizException.class,()->f.service.record(f.scope,"STORE","PR20261020000999"));
 }
 static class Fixture {
  final CatalogScope scope=new CatalogScope("MAIN","ENTITY","ACTOR",false);
  final StoredValueMerchantQueryServiceImpl service;
  final JdbcTemplate jdbc;
  Fixture() {
   var ds=new DriverManagerDataSource("jdbc:h2:mem:alias-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
   jdbc.execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
   jdbc.execute("CREATE TABLE sv_statement(record_no VARCHAR(128),voucher_no VARCHAR(128),tenant_no VARCHAR(64),entity_no VARCHAR(64),store_no VARCHAR(64),type VARCHAR(20),business_no VARCHAR(128),currency VARCHAR(3),signed_amount_minor BIGINT,balance_after_minor BIGINT,occurred_at TIMESTAMP)");
   jdbc.execute("CREATE TABLE ent_usage(tenant_no VARCHAR(64),entity_no VARCHAR(64),store_no VARCHAR(64),usage_no VARCHAR(128),operation_no VARCHAR(128),asset_kind VARCHAR(32))");
   jdbc.execute("INSERT INTO sv_statement VALUES('voucher-r','voucher-r','MAIN','ENTITY','STORE','RECHARGE','recharge','CNY',100,100,CURRENT_TIMESTAMP),('voucher-c','voucher-c','MAIN','ENTITY','STORE','REDEMPTION','operation','CNY',-20,80,CURRENT_TIMESTAMP)");
   jdbc.execute("INSERT INTO ent_usage VALUES('MAIN','ENTITY','STORE','usage','operation','STORED_VALUE')");
   alias(DocumentType.SV_RECHARGE,"PR20261020000001","recharge");alias(DocumentType.ENTITLEMENT_USAGE,"EU20261020000001","usage");
   alias(DocumentType.SV_VOUCHER,"SV20261020000001","voucher-r");alias(DocumentType.SV_VOUCHER,"SV20261020000002","voucher-c");
   TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"alias-statement"),SvStatement.class);
   TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"alias-usage"),EntUsage.class);
   var statements=mock(SvStatementMapper.class);var usages=mock(EntUsageMapper.class);
   when(statements.selectOne(any())).thenAnswer(a->one("sv_statement",a.getArgument(0),SvStatement.class));
   when(usages.selectOne(any())).thenAnswer(a->one("ent_usage",a.getArgument(0),EntUsage.class));
   when(usages.selectList(any())).thenAnswer(a->query("ent_usage",a.getArgument(0),EntUsage.class));
   var json=new ObjectMapper();service=new StoredValueMerchantQueryServiceImpl(mock(SvAccountMapper.class),mock(SvMerchantAccountMapper.class),statements,new StoredValueCursorCodec("k".repeat(32),json),json);
   ReflectionTestUtils.setField(service,"usages",usages);
   ReflectionTestUtils.setField(service,"documentNumbers",new JdbcDocumentNumberRepository(DocumentOwner.PAY,new DataSourceTransactionManager(ds)));
  }
  void alias(DocumentType type,String alias,String no) {jdbc.update("INSERT INTO pay_document_number(document_no,tenant_no,document_type,source_system,source_resource_type,source_no,business_date) VALUES(?,'MAIN',?,'PAY',?,?,CURRENT_DATE)",alias,type.name(),type.name(),no);}
  <T> T one(String table,Wrapper<?> wrapper,Class<T> type) {var rows=query(table,wrapper,type);return rows.isEmpty()?null:rows.getFirst();}
  <T> List<T> query(String table,Wrapper<?> wrapper,Class<T> type) {
   String sql=wrapper.getCustomSqlSegment();var values=new ArrayList<Object>();var m=Pattern.compile("#\\{ew.paramNameValuePairs.([^}]+)}").matcher(sql);var out=new StringBuilder();
   while(m.find()) {values.add(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)wrapper).getParamNameValuePairs().get(m.group(1)));m.appendReplacement(out,"?");}m.appendTail(out);
   return jdbc.query("SELECT * FROM "+table+" "+out,new BeanPropertyRowMapper<>(type),values.toArray());
  }
 }
}
