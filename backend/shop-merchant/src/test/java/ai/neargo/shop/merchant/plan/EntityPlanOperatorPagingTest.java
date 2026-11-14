package ai.neargo.shop.merchant.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class EntityPlanOperatorPagingTest {
  JdbcTemplate db;
  EntityPlanPurchaseServiceImpl service;
  final Actor operator = new Actor("OP", "E1", null, true);
  PlatformPaymentPort payments;
  OfflineCollectionPort cash;

  @BeforeEach void setup() {
    // Keep this connection-independent fixture alive for each query.
    db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:plan_page_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    db.execute("CREATE TABLE mch_entity(tenant_no VARCHAR(32), entity_no VARCHAR(64), deleted INT)");
    db.execute("CREATE TABLE mch_account(tenant_no VARCHAR(32),mch_account_no VARCHAR(64),entity_no VARCHAR(64),user_no VARCHAR(64),is_owner INT,status VARCHAR(16),deleted INT)");
    db.update("INSERT INTO mch_account VALUES('MAIN','MA','E1','U',1,'ACTIVE',0)");
    db.execute("CREATE TABLE mch_plan_purchase(tenant_no VARCHAR(32), entity_no VARCHAR(64), order_no VARCHAR(64))");
    db.update("INSERT INTO mch_entity VALUES('MAIN','E1',0),('MAIN','E2',0)");
    for (int i = 1; i <= 105; i++) db.update("INSERT INTO mch_plan_purchase VALUES('MAIN','E1',?)", String.format("O%03d", i));
    db.update("INSERT INTO mch_plan_purchase VALUES('MAIN','E2','O002'),('OTHER','E1','O004')");
    payments = mock(PlatformPaymentPort.class); cash = mock(OfflineCollectionPort.class);
    service = spy(new EntityPlanPurchaseServiceImpl(db, mock(PlatformTransactionManager.class), mock(ObjectMapper.class), payments, cash, mock(PrivateEvidencePort.class), mock(MoneyPolicyPort.class), mock(StoreOwnershipWritePort.class), e -> e.equals("E1"), Clock.systemUTC(), "P"));
    for (int i = 1; i <= 105; i++) {
      String no = String.format("O%03d", i);
      doReturn(new OrderView(no, "R" + no, "E1", "S1", null, "SUBMITTED", null, null, List.of(), List.of(), "MERCHANT")).when(service).order(operator, no);
    }
  }
  @AfterEach void cleanup() { db.execute("DROP ALL OBJECTS"); }

  @Test void pagesPast100WithoutDuplicatesOrOtherScopes() {
    var numbers = new ArrayList<String>(); String after = null;
    while (true) {
      var page = service.operatorOrders(operator, after, 20);
      if (page.isEmpty()) break;
      numbers.addAll(page.stream().map(OrderView::orderNo).toList()); after = page.getLast().orderNo();
    }
    assertEquals(105, numbers.size()); assertEquals(105, new HashSet<>(numbers).size());
    assertEquals("O001", numbers.getFirst()); assertEquals("O105", numbers.getLast());
    verifyNoInteractions(payments, cash);
  }
  @Test void rejectsBuyerAndOutOfScopeActorBeforeReturningOrders() {
    assertThrows(BizException.class, () -> service.operatorOrders(new Actor("U", "E1", "S1", false), null, 20));
    assertThrows(BizException.class, () -> service.operatorOrders(new Actor("OP", "E2", null, true), null, 20));
    verifyNoInteractions(payments, cash);
  }
  @Test void boundsPageSizeAndTreatsCursorAsData() {
    assertThrows(BizException.class, () -> service.operatorOrders(operator, null, 0));
    assertThrows(BizException.class, () -> service.operatorOrders(operator, null, 101));
    assertEquals(2, service.operatorOrders(operator, "O100", 2).size());
    assertTrue(service.operatorOrders(operator, "zz' OR 1=1 --", 20).isEmpty());
    verifyNoInteractions(payments, cash);
  }
}
