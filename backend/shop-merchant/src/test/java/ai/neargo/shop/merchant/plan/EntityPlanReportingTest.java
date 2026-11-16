package ai.neargo.shop.merchant.plan;

import static org.junit.jupiter.api.Assertions.*;
import static ai.neargo.commerce.api.mall.reporting.PurchaseReporting.ORDER;
import static org.mockito.Mockito.*;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

public class EntityPlanReportingTest {
  JdbcTemplate db; EntityPlanPurchaseServiceImpl service;
  final Actor operator=new Actor("OP",null,null,true);
  static final Filter ALL=new Filter(null,null,null,null,null,null,null,false);
  @BeforeEach void setup() {
    db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:plan_report_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    db.execute("CREATE ALIAS JSON_EXTRACT FOR 'ai.neargo.shop.merchant.plan.EntityPlanReportingTest.extract'");
    db.execute("CREATE ALIAS JSON_UNQUOTE FOR 'ai.neargo.shop.merchant.plan.EntityPlanReportingTest.unquote'");
    db.execute("CREATE TABLE mch_plan_purchase(tenant_no VARCHAR,entity_no VARCHAR,order_no VARCHAR,status VARCHAR,created_at BIGINT,snapshot_json VARCHAR)");
    db.execute("CREATE TABLE mch_plan_purchase_claim(tenant_no VARCHAR,order_no VARCHAR,claim_no VARCHAR,status VARCHAR)");
    order("MAIN","Z","E2","S2","USD",100,3000);order("MAIN","B","E1","S1","USD",9007199254740993L,2000);
    order("MAIN","A","E1","S1","USD",10,2000);order("MAIN","OLD","E1","DELETED_STORE","CNY",12,1000);
    order("OTHER","B","E1","S1","USD",99,5000);
    db.update("INSERT INTO mch_plan_purchase_claim VALUES('MAIN','B','C1','SUBMITTED'),('MAIN','B','C2','DECLINED'),('MAIN','A','C1','SUBMITTED'),('OTHER','OLD','C1','SUBMITTED')");
    service=new EntityPlanPurchaseServiceImpl(db,mock(PlatformTransactionManager.class),JsonMapper.builder().build(),mock(PlatformPaymentPort.class),mock(OfflineCollectionPort.class),mock(PrivateEvidencePort.class),mock(MoneyPolicyPort.class),mock(StoreOwnershipWritePort.class),e->{throw new AssertionError("No per-entity enumeration");},Clock.systemUTC(),"P", org.mockito.Mockito.mock(ai.neargo.shop.merchant.service.StoreOperationalReviewService.class));
  }
  @AfterEach void close() { db.execute("DROP ALL OBJECTS"); }
  void order(String t,String no,String e,String store,String currency,long amount,long time) {
    db.update("INSERT INTO mch_plan_purchase VALUES(?,?,?,?,?,?)",t,e,no,"SUBMITTED",time,"{\"storeNo\":\""+store+"\",\"offer\":{\"price\":{\"currency\":\""+currency+"\",\"minor\":"+amount+"}}}");
  }
  Access restricted() { return new Access("MAIN",false,Set.of("E1")); }
  List<Row> search(Filter f,Cursor cursor,int limit) { return service.searchOperatorOrders(operator,new Query(restricted(),f,cursor,limit)); }
  @Test void authorizedRowsAreFilteredBeforeLimitAndFinancialHistorySurvives() {
    var row=search(ALL,null,1).getFirst();assertEquals("B",row.orderNo());assertEquals("9007199254740993",row.payableMinor());
    assertEquals(List.of("B","A","OLD"),search(ALL,null,10).stream().map(Row::orderNo).toList());
    assertEquals("DELETED_STORE",search(ALL,null,10).getLast().storeNo());
    assertTrue(service.searchOperatorOrders(operator,new Query(new Access("MAIN",false,Set.of()),ALL,null,10)).isEmpty());
    assertEquals(4,service.searchOperatorOrders(operator,new Query(new Access("MAIN",true,Set.of()),ALL,null,10)).size());
  }
  @Test void filtersAndPendingClaimsRunBeforeSqlPageLimit() {
    var f=new Filter("E1","S1",null,"SUBMITTED","USD",2000L,3000L,true);
    assertEquals("SUBMITTED",search(f,null,1).getFirst().claimStatus());
    assertEquals(List.of("B","A"),search(f,null,10).stream().map(Row::orderNo).toList());
    assertTrue(search(new Filter("E2",null,null,null,null,null,null,false),null,10).isEmpty());
    assertTrue(search(new Filter(null,null,"' OR 1=1 --",null,null,null,null,false),null,10).isEmpty());
  }
  @Test void globalCursorAndComparatorHandleCrossModelTies() {
    assertEquals(List.of("A","OLD"),search(ALL,new Cursor(2000,Model.ENTITY,"B"),10).stream().map(Row::orderNo).toList());
    assertEquals(List.of("OLD"),search(ALL,new Cursor(2000,Model.STORE,"Z"),10).stream().map(Row::orderNo).toList());
    var entity=search(ALL,null,1).getFirst();
    var store=new Row(Model.STORE,"Z","E1","S1","USD","1","UNPAID",null,2000);
    assertTrue(ORDER.compare(entity,store)<0);
  }
  @Test void attributionIsInternalTenantConstrainedAndBounded() {
    assertEquals("9007199254740993",service.findOperatorOrderOwners(operator,"MAIN",List.of("B")).getFirst().payableMinor());
    assertThrows(BizException.class,()->service.findOperatorOrderOwners(operator,"OTHER",List.of("B")));
    assertThrows(BizException.class,()->service.findOperatorOrderOwners(operator,"MAIN",Collections.nCopies(501,"B")));
    assertThrows(BizException.class,()->service.searchOperatorOrders(new Actor("BUYER",null,null,false),new Query(restricted(),ALL,null,10)));
    assertThrows(BizException.class,()->service.searchOperatorOrders(new Actor("",null,null,true),new Query(restricted(),ALL,null,10)));
    assertTrue(service.findOperatorOrderOwners(operator,"MAIN",List.of()).isEmpty());
  }
  public static String extract(String json,String path) {
    var node=JsonMapper.builder().build().readTree(json);for(var part:path.substring(2).split("\\."))node=node.path(part);
    return node.isMissingNode()?null:node.toString();
  }
  public static String unquote(String value) { return value==null?null:JsonMapper.builder().build().readTree(value).asText(); }
}
