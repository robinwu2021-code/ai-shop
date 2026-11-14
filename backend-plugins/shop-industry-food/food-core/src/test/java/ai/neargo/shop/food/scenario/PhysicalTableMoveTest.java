package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.dto.TransferCheckReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper;
import ai.neargo.shop.food.mapper.ReservationTableMapper;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.CheckServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real SQL fixtures assert topology moves cannot mutate the source batch or original order bindings. */
class PhysicalTableMoveTest extends FoodWriteGuardFixture {
    final CheckServiceTest helper=new CheckServiceTest();
    void run(ContextConsumer<AssertableApplicationContext> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,TableServiceTest.Areas.class,
                DiningSessionTest.Sessions.class,CheckServiceTest.Checks.class,ReservationTables.class).run(test);
    }
    Fixture fixture(AssertableApplicationContext c) {
        var f=helper.fixture(c);
        f.sql().execute("CREATE TABLE fnb_reservation(tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"store_no VARCHAR(32),reservation_no VARCHAR(32),start_at DATETIME(3),end_at DATETIME(3),"
                +"status VARCHAR(24),deleted INT DEFAULT 0)");
        f.sql().execute("CREATE TABLE fnb_reservation_table(tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"store_no VARCHAR(32),reservation_no VARCHAR(32),table_no VARCHAR(32),deleted INT DEFAULT 0)");
        f.sql().execute("ALTER TABLE fnb_order_batch ADD COLUMN batch_no VARCHAR(32)");
        f.sql().execute("ALTER TABLE fnb_order_batch ADD COLUMN status VARCHAR(24)");
        f.sql().execute("ALTER TABLE fnb_check_batch_membership ADD COLUMN batch_no VARCHAR(32)");
        f.sql().execute("CREATE TABLE fnb_core_order_binding(entity_no VARCHAR(32),binding_no VARCHAR(32),"
                +"batch_no VARCHAR(32),payment_root_no VARCHAR(32),store_order_no VARCHAR(32),status VARCHAR(24),"
                +"version BIGINT DEFAULT 0,deleted INT DEFAULT 0)");
        f.sql().execute("CREATE TABLE fnb_core_line_allocation(entity_no VARCHAR(32),batch_no VARCHAR(32),"
                +"line_no VARCHAR(32),core_item_no VARCHAR(32),quantity DECIMAL(20,6),version BIGINT DEFAULT 0)");
        return f;
    }
    CheckService service(AssertableApplicationContext c) {
        return new CheckServiceImpl(c.getBean(CheckMapper.class),c.getBean(TableAreaMapper.class),
                c.getBean(TableMapper.class),c.getBean(TableOccupancyMapper.class),c.getBean(SeatMapper.class),
                c.getBean(TableOperationMapper.class),c.getBean(GuestSessionMapper.class),c.getBean(ActionLogMapper.class),
                c.getBean(StoreOperationService.class),c.getBean(IdempotencyService.class),
                c.getBean("foodOutboxPublisher",OutboxPublisher.class),c.getBean(ObjectMapper.class),
                c.getBean(PlatformTransactionManager.class),c.getBean(ReservationTableMapper.class));
    }
    void postpay(Fixture f,String check) {
        f.sql().update("UPDATE fnb_check SET pay_mode='POSTPAY' WHERE check_no=?",check);
    }
    void bindOriginalOrder(Fixture f,String check) {
        f.sql().update("INSERT INTO fnb_order_batch(entity_no,check_no,batch_no,status) VALUES('E',?,'BATCH','BOUND')",check);
        f.sql().update("INSERT INTO fnb_check_batch_membership(entity_no,check_no,batch_no) VALUES('E',?,'BATCH')",check);
        f.sql().update("INSERT INTO fnb_core_order_binding(entity_no,binding_no,batch_no,payment_root_no,store_order_no,status) "
                +"VALUES('E','BINDING','BATCH','ORIGINAL_PAYMENT_ROOT','ORIGINAL_STORE_ORDER','BOUND')");
        f.sql().update("INSERT INTO fnb_core_line_allocation(entity_no,batch_no,line_no,core_item_no,quantity) "
                +"VALUES('E','BATCH','LINE','ORIGINAL_ITEM',2.000000)");
    }
    Map<String,List<Map<String,Object>>> trade(Fixture f) {
        return Map.of("batch",f.sql().queryForList("SELECT * FROM fnb_order_batch"),
                "membership",f.sql().queryForList("SELECT * FROM fnb_check_batch_membership"),
                "binding",f.sql().queryForList("SELECT * FROM fnb_core_order_binding"),
                "allocation",f.sql().queryForList("SELECT * FROM fnb_core_line_allocation"),
                "settlement",f.sql().queryForList("SELECT * FROM fnb_settlement_item"),
                "returns",f.sql().queryForList("SELECT * FROM fnb_return"),
                "entitlements",f.sql().queryForList("SELECT * FROM fnb_dining_entitlement"));
    }
    @Test void sameOrderPostpayTransferAndCombinePreserveRealBindings() {
        run(c -> {
            var f=fixture(c); var first=helper.table(c,"T1"); var target=helper.table(c,"T2");
            var third=helper.table(c,"T3"); var s=service(c);
            var opened=s.openCheck("E","A","actor",helper.open(first,2,"EXCLUSIVE"),"open").check();
            postpay(f,opened.checkNo()); bindOriginalOrder(f,opened.checkNo());
            var original=trade(f); var checkBefore=s.getCheck("E","A",opened.checkNo()).check();
            var request=new TransferCheckReq(0L,"原订单转桌",null,List.of(target.tableNo()),
                    List.of(new TableVersionReq(first.tableNo(),1L),new TableVersionReq(target.tableNo(),0L)));
            var moved=s.transferCheck("E","A",opened.checkNo(),"actor",request,"move");
            assertThat(s.transferCheck("E","A",opened.checkNo(),"actor",request,"move")).isEqualTo(moved);
            assertThat(s.getCheck("E","A",opened.checkNo()).check().tableNos()).containsExactly(target.tableNo());
            s.combineTables("E","A",opened.checkNo(),"actor",new CombineTablesReq(1L,
                    List.of(new TableVersionReq(third.tableNo(),0L)),"原台账加桌"),"combine");
            var after=s.getCheck("E","A",opened.checkNo());
            assertThat(after.check().checkNo()).isEqualTo(checkBefore.checkNo());
            assertThat(after.check().payMode()).isEqualTo("POSTPAY");
            assertThat(after.check().businessDate()).isEqualTo(checkBefore.businessDate());
            assertThat(after.check().partySize()).isEqualTo(checkBefore.partySize());
            assertThat(after.check().tableNos()).containsExactlyInAnyOrder(target.tableNo(),third.tableNo());
            assertThat(trade(f)).isEqualTo(original);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_operation WHERE status='APPLIED'",Integer.class))
                    .isEqualTo(2);
        });
    }
    @Test void emptyPostpayCheckCanMoveWithoutCreatingTradeFacts() {
        run(c -> {
            var f=fixture(c); var first=helper.table(c,"T1"); var target=helper.table(c,"T2"); var s=service(c);
            var check=s.openCheck("E","A","actor",helper.open(first,2,"EXCLUSIVE"),"open").check();
            postpay(f,check.checkNo()); var original=trade(f);
            s.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,"后付空台转桌",null,
                    List.of(target.tableNo()),List.of(new TableVersionReq(first.tableNo(),1L),
                            new TableVersionReq(target.tableNo(),0L))),"move");
            assertThat(s.getCheck("E","A",check.checkNo()).check().tableNos()).containsExactly(target.tableNo());
            assertThat(trade(f)).isEqualTo(original);
        });
    }
    @Test void pendingOrNonopenCheckNeverChangesOccupancy() {
        for(String state:List.of("pending","external","SETTLING","STATUS_SETTLING","RELEASED","UNKNOWN_PAY_MODE")) run(c -> {
            var f=fixture(c); var first=helper.table(c,"T1"); var target=helper.table(c,"T2"); var s=service(c);
            var check=s.openCheck("E","A","actor",helper.open(first,2,"EXCLUSIVE"),"open").check();
            postpay(f,check.checkNo()); bindOriginalOrder(f,check.checkNo());
            if(state.equals("pending") || state.equals("external")) {
                f.sql().update("INSERT INTO fnb_table_operation(entity_no,store_no,check_no,target_check_no,operation_no,kind,"
                        +"request_key,request_hash,expected_versions,target_scope,status,reason,resource_operation_ref,deleted) "
                        +"VALUES('E','A','OTHER',?,'PENDING','MERGE','pending','hash','{}','{}',?,'pending',?,1)",
                        check.checkNo(),state.equals("pending")?"CLAIMING":"APPLIED",state.equals("external")?"EXTERNAL":null);
            } else if(state.equals("STATUS_SETTLING")) {
                f.sql().update("UPDATE fnb_check SET status='SETTLING' WHERE check_no=?",check.checkNo());
            } else if(state.equals("UNKNOWN_PAY_MODE")) {
                f.sql().update("UPDATE fnb_check SET pay_mode='OTHER' WHERE check_no=?",check.checkNo());
            } else {
                f.sql().update("UPDATE fnb_check SET service_state=? WHERE check_no=?",state,check.checkNo());
            }
            var before=s.getCheck("E","A",check.checkNo()); var original=trade(f);
            DiningSessionTest.code(() -> s.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,
                    "拒绝待决转桌",null,List.of(target.tableNo()),List.of(new TableVersionReq(first.tableNo(),1L),
                            new TableVersionReq(target.tableNo(),0L))),"move"),ErrorCode.CONFLICT);
            DiningSessionTest.code(() -> s.combineTables("E","A",check.checkNo(),"actor",new CombineTablesReq(0L,
                    List.of(new TableVersionReq(target.tableNo(),0L)),"拒绝待决加桌"),"combine"),ErrorCode.CONFLICT);
            assertThat(s.getCheck("E","A",check.checkNo())).isEqualTo(before);
            assertThat(trade(f)).isEqualTo(original);
        });
    }
    @Test void reservationConflictNeverReleasesSource() {
        run(c -> {
            var f=fixture(c); var first=helper.table(c,"T1"); var target=helper.table(c,"T2"); var s=service(c);
            var check=s.openCheck("E","A","actor",helper.open(first,2,"EXCLUSIVE"),"open").check();
            postpay(f,check.checkNo()); bindOriginalOrder(f,check.checkNo());
            var reservedAt=Instant.now();
            f.sql().update("INSERT INTO fnb_reservation(entity_no,store_no,reservation_no,start_at,end_at,status) "
                    +"VALUES('E','A','RSV',?,?,'CONFIRMED')",Timestamp.from(reservedAt),
                    Timestamp.from(reservedAt.plusSeconds(3600)));
            f.sql().update("INSERT INTO fnb_reservation_table(entity_no,store_no,reservation_no,table_no) "
                    +"VALUES('E','A','RSV',?)",target.tableNo());
            var before=s.getCheck("E","A",check.checkNo()); var original=trade(f);
            DiningSessionTest.code(() -> s.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,
                    "目标已预约",null,List.of(target.tableNo()),List.of(new TableVersionReq(first.tableNo(),1L),
                            new TableVersionReq(target.tableNo(),0L))),"move"),ErrorCode.CONFLICT);
            assertThat(s.getCheck("E","A",check.checkNo())).isEqualTo(before);
            assertThat(trade(f)).isEqualTo(original);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_operation",Integer.class)).isZero();
        });
    }
    @Configuration(proxyBeanMethods=false) static class ReservationTables {
        @Bean MapperFactoryBean<ReservationTableMapper> reservationTables(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var mapper=new MapperFactoryBean<>(ReservationTableMapper.class);
            mapper.setSqlSessionTemplate(template); return mapper;
        }
    }
}
