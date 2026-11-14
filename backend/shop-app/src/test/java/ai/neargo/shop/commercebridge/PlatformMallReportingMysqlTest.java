package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.persistence.JdbcPurchaseOrderQuery;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.pay.service.PlatformCashReporting;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/** Native MySQL JSON, audit time, cursor and menu migration evidence in disposable schemas only. */
@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*")
class PlatformMallReportingMysqlTest {
  String schema;
  JdbcTemplate admin, db;
  DriverManagerDataSource ds;
  PlatformMallReporting reporting;
  JdbcPurchaseOrderQuery stores;
  final ObjectProvider<PlatformCashReporting> cashProvider=mock(ObjectProvider.class);
  final PlatformMallAccess.Context restricted=new PlatformMallAccess.Context(new Access("MAIN",false,Set.of("E1")),"OPS1",Set.of(Model.ENTITY,Model.STORE));
  final Filter filter=new Filter(null,null,null,null,null,null,null,false);

  @BeforeEach void setup() {
    String url=System.getenv("COMMERCE_MYSQL_URL");
    String user=System.getenv().getOrDefault("COMMERCE_MYSQL_USER","root");
    String password=System.getenv().getOrDefault("COMMERCE_MYSQL_PASSWORD","");
    admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
    assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("9."),"Native MySQL9 required");
    schema="platform_reporting_test_"+UUID.randomUUID().toString().replace("-","");
    admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,password);
    db=new JdbcTemplate(ds);
    db.execute("CREATE TABLE mch_entity(tenant_no VARCHAR(32) NOT NULL,entity_no VARCHAR(64) NOT NULL,deleted TINYINT NOT NULL,PRIMARY KEY(tenant_no,entity_no))");
    db.execute("CREATE TABLE mch_store(tenant_no VARCHAR(32) NOT NULL,store_no VARCHAR(64) NOT NULL,entity_no VARCHAR(64) NOT NULL,deleted TINYINT NOT NULL,PRIMARY KEY(tenant_no,store_no))");
    db.execute("CREATE TABLE mch_plan_purchase(tenant_no VARCHAR(32) NOT NULL,entity_no VARCHAR(64) NOT NULL,order_no VARCHAR(64) NOT NULL,status VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,snapshot_json JSON NOT NULL,PRIMARY KEY(tenant_no,order_no))");
    db.execute("CREATE TABLE mch_plan_purchase_claim(tenant_no VARCHAR(32) NOT NULL,order_no VARCHAR(64) NOT NULL,claim_no VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL,PRIMARY KEY(tenant_no,order_no,claim_no))");
    db.execute("CREATE TABLE mall_order(tenant_no VARCHAR(32) NOT NULL,order_no VARCHAR(64) NOT NULL,entity_no VARCHAR(64) NOT NULL,store_no VARCHAR(64) NOT NULL,currency VARCHAR(8) NOT NULL,cash_due_minor BIGINT NOT NULL,pay_status VARCHAR(16) NOT NULL,created_at DATETIME(3) NOT NULL,PRIMARY KEY(tenant_no,order_no))");
    db.execute("CREATE TABLE mall_offline_claim(tenant_no VARCHAR(32) NOT NULL,order_no VARCHAR(64) NOT NULL,claim_no VARCHAR(64) NOT NULL,claim_snapshot JSON NOT NULL,PRIMARY KEY(tenant_no,order_no,claim_no))");
    db.update("INSERT INTO mch_entity VALUES('MAIN','E1',1),('MAIN','E2',0)");
    db.update("INSERT INTO mch_store VALUES('MAIN','S1','E1',0),('MAIN','HISTORY','E1',1)");
    entity("MAIN","B","E1","S1",9007199254740993L,2000);
    entity("MAIN","A","E1","S1",12,2000);
    entity("MAIN","E_OLD","E1","HISTORY",15,1000);
    entity("MAIN","E_HIDDEN","E2","S2",20,5000);
    entity("OTHER","B","E1","S1",99,7000);
    store("MAIN","Z","E1","S1",Long.MAX_VALUE,2000);
    store("MAIN","Y","E1","S1",18,2000);
    store("MAIN","S_OLD","E1","HISTORY",19,500);
    store("MAIN","S_HIDDEN","E2","S2",25,6000);
    store("OTHER","Z","E1","S1",99,7000);
    db.update("INSERT INTO mch_plan_purchase_claim VALUES('MAIN','B','C1','SUBMITTED'),('MAIN','B','C2','DECLINED'),('MAIN','A','C1','DECLINED'),('OTHER','E_OLD','C1','SUBMITTED')");
    claim("Z","C1","SUBMITTED",1);claim("Z","C2","DECLINED",2);claim("Y","C1","REVIEWING",1);
    stores=new JdbcPurchaseOrderQuery(ds);
    var entities=new EntityPlanPurchaseServiceImpl(db,new DataSourceTransactionManager(ds),JsonMapper.builder().build(),mock(PlatformPaymentPort.class),mock(OfflineCollectionPort.class),mock(PrivateEvidencePort.class),mock(MoneyPolicyPort.class),mock(StoreOwnershipWritePort.class),e->{throw new AssertionError("No per-entity enumeration");},Clock.systemUTC(),"P", org.mockito.Mockito.mock(ai.neargo.shop.merchant.service.StoreOperationalReviewService.class));
    reporting=new PlatformMallReporting(stores,entities,cashProvider);
  }

  @AfterEach void cleanup() { if(admin!=null && schema!=null) admin.execute("DROP DATABASE "+schema); }

  void entity(String tenant,String no,String entity,String store,long minor,long at) {
    db.update("INSERT INTO mch_plan_purchase VALUES(?,?,?,?,?,CAST(? AS JSON))",tenant,entity,no,"SUBMITTED",at,
        "{\"storeNo\":\""+store+"\",\"offer\":{\"price\":{\"currency\":\"USD\",\"minor\":"+minor+"}}}");
  }
  void store(String tenant,String no,String entity,String store,long minor,long at) {
    db.update("INSERT INTO mall_order VALUES(?,?,?,?,?,?,?,?)",tenant,no,entity,store,"USD",minor,"UNPAID",LocalDateTime.ofInstant(Instant.ofEpochMilli(at),ZoneOffset.UTC));
  }
  void claim(String order,String no,String status,long at) {
    db.update("INSERT INTO mall_offline_claim VALUES('MAIN',?,?,CAST(? AS JSON))",order,no,"{\"status\":\""+status+"\",\"submittedAt\":"+at+"}");
  }

  @Test void mergesNativeSourcePagesAcrossModelTiesWithScopeBeforeLimit() {
    var rows=new ArrayList<Row>();String after=null;int pages=0;
    do {
      var page=reporting.orders(restricted,filter,after,2);rows.addAll(page.items());after=page.nextCursor();pages++;
      assertTrue(pages<=3,"Cursor must make progress");
    } while(after!=null);
    assertEquals(3,pages);
    assertEquals(List.of("ENTITY/B","ENTITY/A","STORE/Z","STORE/Y","ENTITY/E_OLD","STORE/S_OLD"),rows.stream().map(r->r.model()+"/"+r.orderNo()).toList());
    assertEquals("9007199254740993",rows.getFirst().payableMinor());assertEquals(Long.toString(Long.MAX_VALUE),rows.get(2).payableMinor());
    assertEquals(6,rows.stream().map(r->r.model()+"/"+r.orderNo()).distinct().count());
    var all=new PlatformMallAccess.Context(new Access("MAIN",true,Set.of()),"OPS1",restricted.models());
    assertEquals(8,reporting.orders(all,filter,null,20).items().size());
    var empty=new PlatformMallAccess.Context(new Access("MAIN",false,Set.of()),"OPS1",restricted.models());
    assertTrue(reporting.orders(empty,filter,null,20).items().isEmpty());
  }

  @Test void nativeJsonPendingAndSourceFiltersRunBeforePagination() {
    var pending=new Filter("E1","S1",null,null,"USD",1500L,3000L,true);
    var page=reporting.orders(restricted,pending,null,10);
    assertEquals(List.of("B","Z","Y"),page.items().stream().map(Row::orderNo).toList());
    assertEquals(List.of("SUBMITTED","SUBMITTED","REVIEWING"),page.items().stream().map(Row::claimStatus).toList());
    assertEquals("B",reporting.orders(restricted,pending,null,1).items().getFirst().orderNo());
    var entityOnly=new PlatformMallAccess.Context(restricted.access(),"OPS1",Set.of(Model.ENTITY));
    assertEquals(List.of("B"),reporting.orders(entityOnly,new Filter(null,null,"B","SUBMITTED","USD",null,null,true),null,1).items().stream().map(Row::orderNo).toList());
    var storeOnly=new PlatformMallAccess.Context(restricted.access(),"OPS1",Set.of(Model.STORE));
    assertEquals(List.of("Y"),reporting.orders(storeOnly,new Filter(null,null,"Y","UNPAID","USD",null,null,true),null,1).items().stream().map(Row::orderNo).toList());
    assertTrue(reporting.orders(restricted,new Filter(null,null,"' OR 1=1 --",null,null,null,null,false),null,10).items().isEmpty());
    assertTrue(reporting.orders(restricted,new Filter(null,null,null,null,"CNY",null,null,false),null,10).items().isEmpty());
  }

  @Test void tombstoneHistoryAndCashAttributionPreserveExactMinorValues() {
    var historical=new Filter(null,"HISTORY",null,null,null,null,null,false);
    assertEquals(List.of("E_OLD","S_OLD"),reporting.orders(restricted,historical,null,10).items().stream().map(Row::orderNo).toList());
    assertEquals(Optional.of("E1"),new ShopCommerceStoreAdapter(ds,new DataSourceTransactionManager(ds)).financialOwner(new StoreRef("MAIN","HISTORY")));
    PlatformCashReporting cash=(q,consumer)->consumer.accept(List.of(
        new PlatformCashReporting.Fact("P_ENTITY","B",null,"PAY","USD",9007199254740993L,3000,"NORMAL",true,null),
        new PlatformCashReporting.Fact("P_STORE","Z",null,"PAY","USD",Long.MAX_VALUE,2000,"NORMAL",true,null)));
    when(cashProvider.getIfAvailable()).thenReturn(cash);
    var result=reporting.cash(restricted,0,4000,"USD",null,null,null,10);
    assertEquals(List.of("9007199254740993",Long.toString(Long.MAX_VALUE)),result.items().stream().map(PlatformMallReporting.CashRow::amountMinor).toList());
    assertEquals(Set.of(Model.ENTITY,Model.STORE),new HashSet<>(result.items().stream().map(PlatformMallReporting.CashRow::model).toList()));
    assertEquals("9007199254740993",result.summary().stream().filter(s->s.model()==Model.ENTITY).findFirst().orElseThrow().receivedMinor());
    assertEquals(Long.toString(Long.MAX_VALUE),result.summary().stream().filter(s->s.model()==Model.STORE).findFirst().orElseThrow().receivedMinor());
  }

  @Test void nativeV670AddsOnlyFourSuperAdminGrantsAndPreservesExistingRows() throws Exception {
    String ddl=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
    for(String table:List.of("sys_function_point","sys_role","sys_role_point")) {
      var matcher=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+"\\s*\\(.*?\\n\\);").matcher(ddl);
      assertTrue(matcher.find(),table);db.execute(matcher.group());
    }
    for(String role:List.of("SUPER_ADMIN","FINANCE","PLATFORM_OPS","CUSTOM"))
      db.update("INSERT INTO sys_role(role_code,name,end_code,created_at,updated_at) VALUES(?,?,'OPS',NOW(),NOW())",role,role);
    db.update("INSERT INTO sys_function_point(point_code,function_code,name,perm_code,created_at,updated_at) VALUES('OLD_POINT','OPS_COMMERCE','Existing','commerce:mall:order:read',NOW(),NOW())");
    db.update("INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) VALUES('CUSTOM','OLD_POINT','OPS',NOW(),NOW())");
    var oldPoint=db.queryForMap("SELECT * FROM sys_function_point WHERE point_code='OLD_POINT'");
    var oldGrant=db.queryForMap("SELECT * FROM sys_role_point WHERE point_code='OLD_POINT'");
    for(int repeat=0;repeat<2;repeat++) try(var connection=ds.getConnection()) {
      ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V670__ops_platform_purchase_reporting.sql"));
    }
    assertEquals(oldPoint,db.queryForMap("SELECT * FROM sys_function_point WHERE point_code='OLD_POINT'"));
    assertEquals(oldGrant,db.queryForMap("SELECT * FROM sys_role_point WHERE point_code='OLD_POINT'"));
    assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM sys_function_point",Integer.class));
    assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM sys_role_point",Integer.class));
    assertEquals(List.of("SUPER_ADMIN"),db.queryForList("SELECT DISTINCT role_code FROM sys_role_point WHERE point_code<>'OLD_POINT'",String.class));
    assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM sys_function_point WHERE function_code='OPS_COMMERCE' AND point_type='MENU' AND ui_ready=1 AND perm_code LIKE 'commerce:platform:%'",Integer.class));
    assertEquals(Set.of("/mall?tab=global-orders","/mall?tab=global-claims","/mall?tab=cash-summary","/mall?tab=cash-details"),new HashSet<>(db.queryForList("SELECT href FROM sys_function_point WHERE point_code<>'OLD_POINT'",String.class)));
  }
}
