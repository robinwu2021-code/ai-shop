package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.*;
import ai.neargo.shop.pay.points.admin.*;
import ai.neargo.shop.pay.points.admin.impl.PointsWorkbenchServiceImpl;
import ai.neargo.shop.pay.points.statement.*;
import ai.neargo.shop.pay.points.statement.impl.StatementConfirmationStateImpl;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.payout.*;
import ai.neargo.shop.pay.points.refund.*;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.*;
import ai.neargo.shop.pay.points.posting.impl.PointsPostingServiceImpl;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayRightsWorkbenchReservationTest {
 @Test void workbenchReservesBeforeJournalLocksForAllVoucherProducingOperations() {
  var f=new Fixture();var w=f.workbench();
  w.draftStatements(new PointsWorkbenchService.StatementDraft("draft","POINT","STORE",1,2,"reason"),"ACTOR");
  assertEquals(400,f.bound.get());
  for(var action:PointsWorkbenchService.StatementAction.values())
   w.changeStatement(new PointsWorkbenchService.StatementChange(action.name(),"POINT","STORE","statement",1,action,"reason"),"ACTOR");
  w.reviseRefund(new PointsWorkbenchService.Revision("refund","POINT","STORE","refund",1,"reason"),"ACTOR");
  w.prepareReceivable(new PointsWorkbenchService.ReceivableRecovery("receivable","POINT","STORE","debt",1,10,"reason"),"ACTOR");
  assertEquals(406,f.bound.get());
 }
 @Test void automaticConfirmationReservesBeforeItsExistingStateTransaction() {
  var f=new Fixture();var metadata=mock(StatementConfirmationStore.class);var rows=mock(StatementStore.class);
  when(rows.lock("statement")).thenReturn(f.head());
  when(metadata.lock("statement",1)).thenReturn(Map.of("state","DRAFT","notified_at",1L,"deadline",2L));
  ObjectProvider<StoreStatementService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(f.statements);
  var state=transactional(new StatementConfirmationStateImpl(metadata,f.read,f.config,rows,provider),f,true);
  assertTrue(((StatementConfirmationState)state).confirm("statement",1,3));assertEquals(1,f.bound.get());verify(metadata).done("statement",1);
 }
 @Test void infrastructureAdvisorIsAppliedByEnableTransactionManagementWithoutAspectJAutoProxy() {
  try(var context=new AnnotationConfigApplicationContext(InfrastructureConfig.class)) {
   var result=context.getBean(PointsPostingService.class).post(new Voucher("BIZ","POINT","CNY",new RedeemRate(1,1),List.of(),List.of(),List.of(),0,List.of(),"{}"));
   assertTrue(result.documentNo().startsWith("PTV"));
  }
 }
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 @EnableTransactionManagement
 @Import(PayRightsNumberReservations.class)
 static class InfrastructureConfig {
  @Bean Fixture fixture(){return new Fixture();}
  @Bean("payTxManager") DataSourceTransactionManager manager(Fixture f){return f.manager;}
  @Bean("payDocumentNumbers") JdbcDocumentNumberRepository lookup(Fixture f){return f.repository;}
  @Bean("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers(Fixture f){return f.numbers;}
  @Bean PayRightsDocumentNumbers aliases(Fixture f){return new PayRightsDocumentNumbers(f.numbers);}
  @Bean PointsPostingService points(Fixture f){
   var store=mock(PostingStore.class);
   when(store.claim(any(),anyString(),anyString())).thenAnswer(a->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return new PostingResult(a.getArgument(2),false,"{}");});
   return new PointsPostingServiceImpl(store);
  }
 }
 static Object transactional(Object target,Fixture f,boolean transaction) {
  var factory=new ProxyFactory(target);factory.addAdvisor(new PayRightsNumberReservations(f.numbers));
  if(transaction)factory.addAdvisor(new DefaultPointcutAdvisor(new TransactionInterceptor(f.manager,new MatchAlwaysTransactionAttributeSource())));
  return factory.getProxy();
 }
 static class Fixture {
  final DataSourceTransactionManager manager;
  final JdbcDocumentNumberRepository repository;
  final DocumentNumberCoordinator numbers;
  final AtomicInteger issued=new AtomicInteger(),bound=new AtomicInteger();
  final PointsReadStore read=mock(PointsReadStore.class);
  final PointsConfigurationStore config=mock(PointsConfigurationStore.class);
  final StoreStatementService statements=mock(StoreStatementService.class);
  final StatementPayoutService payouts=mock(StatementPayoutService.class);
  final PointsRefundCollectionService collections=mock(PointsRefundCollectionService.class);
  final PointsReceivableRecoveryService receivables=mock(PointsReceivableRecoveryService.class);
  final ObjectMapper json=new ObjectMapper();
  final StoreStatementService.Snapshot snapshot=new StoreStatementService.Snapshot("statement",new StoreStatementService.Period("POINT","STORE",1,2),List.of(),List.of(new StoreStatementService.Revision(1,List.of(),10,"ACTOR",null)),"DRAFT",null);
  Fixture() {
   var ds=new DriverManagerDataSource("jdbc:h2:mem:workbench-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");manager=new DataSourceTransactionManager(ds);
   new JdbcTemplate(ds).execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
   repository=new JdbcDocumentNumberRepository(DocumentOwner.PAY,manager);var generator=mock(DocumentNumberGenerator.class);
   when(generator.reserve(any(DocumentType.class),any(Instant.class))).thenAnswer(a->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new ReservedDocumentNumber(a.getArgument(0),LocalDate.of(2026,10,20),"PTV20261020"+String.format("%06d",issued.incrementAndGet()));});
   numbers=new DocumentNumberCoordinator(repository,generator,manager);
   when(config.lockStatement("statement")).thenAnswer(a->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return head();});
   when(read.position(PositionMove.Kind.STATEMENT.table(),"statement")).thenReturn(head());
   when(read.position(PositionMove.Kind.REFUND.table(),"refund")).thenReturn(Map.of("type_code","POINT","version",1L,"payload_json","{\"command\":{\"payment\":{\"storeNo\":\"STORE\"}}}"));
   when(config.lockReceivable("debt")).thenReturn(Map.of("type_code","POINT","store_no","STORE","version",1L));
   when(statements.draftGroups(any(),anyString())).thenAnswer(a->{bind(400);return List.of(snapshot);});
   when(statements.confirm(anyString(),anyLong(),anyString())).thenAnswer(a->{bind(1);return snapshot;});
   when(statements.dispute(anyString(),anyLong(),anyString(),anyString())).thenAnswer(a->{bind(1);return snapshot;});
   when(statements.redraft(anyString(),anyLong(),anyString())).thenAnswer(a->{bind(1);return snapshot;});
   var ticket=new ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket("ticket",10,0,10,"idem",null);
   when(payouts.prepare(anyString(),anyString(),anyLong(),anyString())).thenAnswer(a->{bind(1);return ticket;});
   when(collections.reviseFailed("refund")).thenAnswer(a->{bind(1);return null;});
   var command=mock(ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand.class);when(command.ticketNo()).thenReturn("ticket");
   when(receivables.prepare(anyString(),anyString(),anyLong())).thenAnswer(a->{bind(1);return command;});
  }
  Map<String,Object> head(){return Map.of("type_code","POINT","payload_json",json.writeValueAsString(snapshot));}
  void bind(int count){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());for(int i=0;i<count;i++)numbers.bind(DocumentType.POINTS_VOUCHER,new DocumentSource("MAIN","PAY","POINTS_VOUCHER","V"+bound.incrementAndGet()));}
  PointsWorkbenchService workbench(){
   var facts=mock(PointsFactStore.class);when(facts.lockInbox(anyString())).thenReturn(null);
   var journal=(PointsOperatorJournal)transactional(new PointsOperatorJournal(config,facts),this,true);
   var clocks=mock(ObjectProvider.class);
   return (PointsWorkbenchService)transactional(new PointsWorkbenchServiceImpl(journal,read,null,config,statements,payouts,receivables,collections,null,null,clocks),this,false);
  }
 }
}
