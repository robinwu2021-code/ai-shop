package ai.neargo.shop.scenario;
import ai.neargo.shop.pay.points.statement.*;
import ai.neargo.shop.pay.points.store.StatementConfirmationStore;
import ai.neargo.shop.pay.points.statement.impl.StatementConfirmationServiceImpl;
import ai.neargo.shop.spi.points.StatementNoticePort;
import org.junit.jupiter.api.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringJUnitConfig(PointsStatementConfirmationDatabaseTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:points_statement_confirm;MODE=MySQL;DATABASE_TO_LOWER=TRUE","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver"})
class PointsStatementConfirmationDatabaseTest {
 @Configuration @Import(PointsPayoutPreparationTest.Config.class)
 static class Config {
  @Bean @Primary StoreStatementService autoConfirmTarget(){return mock(StoreStatementService.class);}
 }
 @Autowired @Qualifier("payDataSource") DataSource ds;
 @Autowired @Qualifier("payTxManager") PlatformTransactionManager tx;
 @Autowired StatementConfirmationState state;
 @Autowired StatementConfirmationStore metadata;
 @Autowired @Qualifier("autoConfirmTarget") StoreStatementService target;
 JdbcTemplate db(){return new JdbcTemplate(ds);}
 @BeforeAll void schema(){PointsPostingDatabaseTest.initializeSchema(ds);}
 @BeforeEach void resetTarget(){reset(target);}
 String fixture(String status,long revision){
  String no="SC"+UUID.randomUUID().toString().replace("-","").substring(0,20),type="T"+no;
  db().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status,deduct_ratio_bp,idle_days) VALUES(?,'CN','CNY',1,1,'ACTIVE',10000,30)",type);
  db().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
  var snapshot=new StoreStatementService.Snapshot(no,new StoreStatementService.Period(type,"STORE",1,2),List.of(),List.of(new StoreStatementService.Revision(revision,List.of(),10,"MAKER",null)),status,null);
  db().update("INSERT INTO pts_store_statement(position_no,type_code,state,original_points,payload_json,store_no,period_from,period_to,revision,route_key) VALUES(?,?,?,10,?,'STORE',1,2,?,'ROUTE')",no,type,status,new ObjectMapper().writeValueAsString(snapshot),revision);
  metadata.publish(no,revision,type,"M","STORE",status,1);
  return no;
 }
 String type(String no){return db().queryForObject("SELECT type_code FROM pts_store_statement WHERE position_no=?",String.class,no);}
 @Test void repeated_notice_does_not_extend_original_deadline(){
  String no=fixture("DRAFT",1);state.notified(no,1,100,200);state.notified(no,1,150,250);
  assertThat(db().queryForObject("SELECT deadline FROM pts_statement_confirmation WHERE statement_no=?",Long.class,no)).isEqualTo(200);
  assertThat(state.confirm(no,1,199)).isFalse();verifyNoInteractions(target);
  assertThat(state.confirm(no,1,200)).isTrue();verify(target).confirm(no,1,"SYSTEM:STATEMENT_AUTO_CONFIRM");
  assertThat(state.confirm(no,1,300)).isFalse();
 }
 @Test void unnotified_disputed_and_old_revision_cannot_auto_confirm(){
  String no=fixture("DRAFT",2);assertThat(state.confirm(no,2,1000)).isFalse();
  metadata.publish(no,1,type(no),"M","STORE","DRAFT",1);db().update("UPDATE pts_statement_confirmation SET notified_at=1,deadline=2 WHERE statement_no=?",no);
  assertThat(state.confirm(no,1,1000)).isFalse();
  String disputed=fixture("DISPUTED",1);db().update("UPDATE pts_statement_confirmation SET notified_at=1,deadline=2 WHERE statement_no=?",disputed);
  assertThat(state.confirm(disputed,1,1000)).isFalse();verifyNoInteractions(target);
 }
 @Test void failed_notice_never_starts_deadline_or_confirms(){
  String no=fixture("DRAFT",1);var notice=mock(StatementNoticePort.class);when(notice.deliver(any())).thenReturn(false);
  var result=new StatementConfirmationServiceImpl(metadata,notice,state,7).run(type(no),10,1000);
  assertThat(result.processed()).isZero();assertThat(result.deferred()).isEqualTo(1);
  assertThat(db().queryForObject("SELECT deadline FROM pts_statement_confirmation WHERE statement_no=?",Long.class,no)).isNull();verifyNoInteractions(target);
 }
 @Test void financial_confirmation_failure_rolls_back_metadata_writes(){
  String no=fixture("DRAFT",1);state.notified(no,1,1,2);
  when(target.confirm(eq(no),eq(1L),anyString())).thenAnswer(i->{db().update("UPDATE pts_statement_confirmation SET state='CORRUPT' WHERE statement_no=?",no);throw new IllegalStateException("POSTING_FAILED");});
  assertThatThrownBy(()->state.confirm(no,1,10)).hasMessageContaining("POSTING_FAILED");
  assertThat(db().queryForObject("SELECT state FROM pts_statement_confirmation WHERE statement_no=?",String.class,no)).isEqualTo("DRAFT");
 }
 @Test void publication_rolls_back_with_pay_transaction(){
  String no=fixture("DRAFT",1);
  assertThatThrownBy(()->new TransactionTemplate(tx).execute(s->{metadata.publish(no,2,type(no),"M","STORE","DRAFT",2);throw new IllegalStateException("ROLLBACK");})).hasMessage("ROLLBACK");
  assertThat(db().queryForObject("SELECT COUNT(*) FROM pts_statement_confirmation WHERE statement_no=? AND revision=2",Long.class,no)).isZero();
  assertThat(db().queryForObject("SELECT state FROM pts_statement_confirmation WHERE statement_no=?",String.class,no)).isEqualTo("DRAFT");
 }
}
