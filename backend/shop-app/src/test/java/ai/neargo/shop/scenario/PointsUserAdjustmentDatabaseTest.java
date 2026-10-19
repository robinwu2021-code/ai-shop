package ai.neargo.shop.scenario;
import ai.neargo.shop.pay.points.admin.*;
import ai.neargo.shop.pay.points.admin.impl.PointsAdjustmentServiceImpl;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import org.junit.jupiter.api.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringJUnitConfig(PointsPayoutPreparationTest.Config.class)
@Import({PointsAdjustmentServiceImpl.class,PointsOperatorJournal.class})
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:points_user_adjust;MODE=MySQL;DATABASE_TO_LOWER=TRUE","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsUserAdjustmentDatabaseTest {
 @Autowired @Qualifier("payDataSource") DataSource ds;
 @Autowired PointsPostingService posting;
 @Autowired PointsAdjustmentService service;
 JdbcTemplate db(){return new JdbcTemplate(ds);}
 @BeforeAll void schema(){PointsPostingDatabaseTest.initializeSchema(ds);
 new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V468__points_user_adjustment.sql")).execute(ds);
 }
 String fixture(){
  String t="AD"+UUID.randomUUID().toString().replace("-","").substring(0,20);
  db().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status,deduct_ratio_bp,idle_days) VALUES(?,'CN','CNY',1,1,'ACTIVE',10000,30)",t);
  db().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",t);
  var e=new EvidenceRef("INIT:"+t,"CNY",100);posting.recordEvidence(e);
  posting.post(new Voucher("INIT:"+t,t,"CNY",new RedeemRate(1,1),List.of(new PointsMove(new AccountKey(t,HolderType.MINT,"PLATFORM",Bucket.ISSUED),-100),new PointsMove(new AccountKey(t,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE),100)),List.of(new FundMove(FundBucket.U,100)),List.of(),0,List.of(e),"{}"));
  return t;
 }
 PointsAdjustmentService.Submission sub(String t,String no,long p,PointsAdjustmentService.Direction d){return new PointsAdjustmentService.Submission("SUB:"+no,no,t,"U",d,p,"correction");}
 PointsAdjustmentService.Review review(String t,String no){return new PointsAdjustmentService.Review("REV:"+no,no,t,0,true,"approved");}
 @Test void real_journal_replays_once_and_rejects_changed_payload(){
  String t=fixture(),n="A"+t;var s=sub(t,n,20,PointsAdjustmentService.Direction.INCREASE);service.submit(s,"MAKER");service.submit(s,"MAKER");
  assertThatThrownBy(()->service.submit(sub(t,n,21,PointsAdjustmentService.Direction.INCREASE),"MAKER")).hasMessageContaining("CONFLICT");
  service.review(review(t,n),"CHECKER");service.review(review(t,n),"CHECKER");
  assertThat(db().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?",Long.class,t)).isEqualTo(80);
  assertThat(db().queryForObject("SELECT available_points FROM pts_account WHERE type_code=? AND holder_type='USER'",Long.class,t)).isEqualTo(20);
 }
 @Test void budget_failure_rolls_back_approval_and_journal(){
  String t=fixture(),n="A"+t;service.submit(sub(t,n,101,PointsAdjustmentService.Direction.INCREASE),"MAKER");
  assertThatThrownBy(()->service.review(review(t,n),"CHECKER")).isInstanceOf(IllegalStateException.class);
  assertThat(db().queryForObject("SELECT state FROM pts_user_adjustment WHERE adjustment_no=?",String.class,n)).isEqualTo("SUBMITTED");
  assertThat(db().queryForObject("SELECT version FROM pts_user_adjustment WHERE adjustment_no=?",Long.class,n)).isZero();
  assertThat(db().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?",Long.class,t)).isEqualTo(100);
 }
 @Test void decrease_cannot_consume_pending_or_held(){
  String t=fixture(),n="A"+t;service.submit(sub(t,n,10,PointsAdjustmentService.Direction.INCREASE),"MAKER");service.review(review(t,n),"CHECKER");
  posting.post(new Voucher("SHIFT:"+t,t,"CNY",new RedeemRate(1,1),List.of(new PointsMove(new AccountKey(t,HolderType.USER,"U",Bucket.AVAILABLE),-8),new PointsMove(new AccountKey(t,HolderType.USER,"U",Bucket.PENDING),4),new PointsMove(new AccountKey(t,HolderType.USER,"U",Bucket.HELD),4)),List.of(),List.of(),0,List.of(),"{}"));
  String d="D"+t;service.submit(sub(t,d,3,PointsAdjustmentService.Direction.DECREASE),"MAKER");assertThatThrownBy(()->service.review(review(t,d),"CHECKER")).hasMessageContaining("INSUFFICIENT");
  String ok="E"+t;service.submit(sub(t,ok,2,PointsAdjustmentService.Direction.DECREASE),"MAKER");service.review(review(t,ok),"CHECKER");
  assertThat(db().queryForObject("SELECT pending_points+held_points FROM pts_account WHERE type_code=? AND holder_type='USER'",Long.class,t)).isEqualTo(8);
  assertThat(db().queryForObject("SELECT issuable_points FROM pts_account WHERE type_code=? AND holder_type='ISSUE'",Long.class,t)).isEqualTo(92);
  assertThat(db().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?",Long.class,t)).isEqualTo(92);
  assertThat(db().queryForObject("SELECT c_minor FROM pts_fund_pool WHERE type_code=?",Long.class,t)).isEqualTo(8);
 }
}
