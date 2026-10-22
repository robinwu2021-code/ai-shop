package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SeatReq;
import ai.neargo.shop.food.dto.TableVO;
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
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.service.impl.CheckServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class CheckServiceTest extends FoodWriteGuardFixture {
    void run(ContextConsumer<AssertableApplicationContext> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,TableServiceTest.Areas.class,
                DiningSessionTest.Sessions.class,Checks.class).run(test);
    }
    Fixture fixture(AssertableApplicationContext c) {
        var f=new DiningSessionTest().fixture(c);
        String common="id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                +"version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        for(String field:List.of("check_no VARCHAR(32)","seats INT","started_at DATETIME(3)",
                "sharing_mode VARCHAR(16) DEFAULT 'EXCLUSIVE'","resource_claim_no VARCHAR(32)"))
            f.sql().execute("ALTER TABLE fnb_table_occupancy ADD COLUMN "+field);
        f.sql().execute("CREATE TABLE fnb_check ("+common+"check_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),business_date DATE,"
                +"policy_no VARCHAR(32),party_size INT,pay_mode VARCHAR(16),status VARCHAR(24),opened_at DATETIME(3),"
                +"closed_at DATETIME(3),service_employee_no VARCHAR(32),service_state VARCHAR(16))");
        f.sql().execute("CREATE TABLE fnb_seat ("+common+"seat_no VARCHAR(32) UNIQUE,check_no VARCHAR(32),label VARCHAR(32),"
                +"guest_ref VARCHAR(32),UNIQUE(check_no,label))");
        String jsonType="true".equals(System.getenv("FOOD_TEST_DISPOSABLE")) ? "JSON":"VARCHAR(10000)";
        f.sql().execute("CREATE TABLE fnb_table_operation ("+common+"operation_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),"
                +"kind VARCHAR(24),check_no VARCHAR(32),target_check_no VARCHAR(32),request_key VARCHAR(64),request_hash CHAR(64),"
                +"expected_versions "+jsonType+",target_scope "+jsonType+",resource_operation_ref VARCHAR(64),status VARCHAR(24),reason VARCHAR(255),"
                +"UNIQUE(store_no,kind,request_key))");
        for(String name:List.of("fnb_order_batch","fnb_check_batch_membership","fnb_settlement_item","fnb_return","fnb_dining_entitlement"))
            f.sql().execute("CREATE TABLE "+name+" (entity_no VARCHAR(32),check_no VARCHAR(32),deleted INT DEFAULT 0)");
        f.sql().execute("CREATE TABLE fnb_check_cart(entity_no VARCHAR(32),check_no VARCHAR(32),status VARCHAR(16))");
        c.getBean(StoreOperationService.class).createPolicy("E","A","actor",new CreatePolicyReq("UTC",0,
                Instant.parse("2026-01-01T00:00:00Z"),"PREPAY",List.of(),null,0L),"policy");
        return f;
    }
    TableVO table(AssertableApplicationContext c,String code) {
        return c.getBean(TableService.class).createTable("E","A","actor",new CreateTableReq("AREA1",code,code,4,0,null),code).table();
    }
    OpenCheckReq open(TableVO t,int party,String mode) {
        return new OpenCheckReq(List.of(t.tableNo()),party,"PREPAY",null,
                List.of(new TableVersionReq(t.tableNo(),t.version())),mode,List.of(),null);
    }
    @Test void opensAtomicallyAndReplaysOriginalCheck() {
        run(c -> {
            var f=fixture(c);var t=table(c,"T1");var s=c.getBean(CheckService.class);var req=open(t,3,"EXCLUSIVE");
            var first=s.openCheck("E","A","actor",req,"open");
            assertThat(first.check().status()).isEqualTo("OPEN");assertThat(first.check().partySize()).isEqualTo(3);
            assertThat(first.check().businessDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
            assertThat(first.check().tableNos()).containsExactly(t.tableNo());
            assertThat(s.openCheck("E","A","actor",req,"open")).isEqualTo(first);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT sharing_mode FROM fnb_table_occupancy",String.class)).isEqualTo("EXCLUSIVE");
            assertThat(f.sql().queryForObject("SELECT turnover_state FROM fnb_table",String.class)).isEqualTo("IN_USE");
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_table",Long.class)).isEqualTo(1);
            DiningSessionTest.code(() -> s.openCheck("E","A","actor",open(t,2,"EXCLUSIVE"),"other"),ErrorCode.CONFLICT);
        });
    }
    @Test void permitsNoTableAndRejectsUnverifiedCapabilitiesAndForeignResources() {
        run(c -> {
            fixture(c);var t=table(c,"T1");var s=c.getBean(CheckService.class);
            var noTable=new OpenCheckReq(List.of(),1,"PREPAY",null,List.of(),"EXCLUSIVE",List.of(),null);
            assertThat(s.openCheck("E","A","actor",noTable,"fast").check().tableNos()).isEmpty();
            DiningSessionTest.code(() -> s.openCheck("E","B","actor",open(t,2,"SHARED"),"foreign"),ErrorCode.NOT_FOUND);
            DiningSessionTest.code(() -> s.openCheck("E","A","actor",open(t,5,"SHARED"),"too-many"),ErrorCode.CONFLICT);
            DiningSessionTest.code(() -> s.openCheck("E","A","actor",new OpenCheckReq(List.of(),1,"POSTPAY",null,List.of(),"EXCLUSIVE",List.of(),null),"postpay"),ErrorCode.FORBIDDEN);
            DiningSessionTest.code(() -> s.openCheck("E","A","actor",new OpenCheckReq(List.of(),1,"PREPAY","reservation",List.of(),"EXCLUSIVE",List.of(),null),"res"),ErrorCode.FORBIDDEN);
        });
    }
    @Test void sharedChecksRespectCapacityAndKeepGroupsIndependent() {
        run(c -> {
            var f=fixture(c);var t=table(c,"T1");var s=c.getBean(CheckService.class);
            var first=s.openCheck("E","A","actor",open(t,2,"SHARED"),"one");
            var second=s.openSharedCheck("E","A",t.tableNo(),"actor",new OpenSharedCheckReq(1L,2,"PREPAY"),"two");
            assertThat(second.check().checkNo()).isNotEqualTo(first.check().checkNo());
            assertThat(f.sql().queryForObject("SELECT SUM(seats) FROM fnb_table_occupancy WHERE ended_at IS NULL",Integer.class)).isEqualTo(4);
            DiningSessionTest.code(() -> s.openSharedCheck("E","A",t.tableNo(),"actor",new OpenSharedCheckReq(2L,1,"PREPAY"),"full"),ErrorCode.CONFLICT);
            assertThat(s.getCheck("E","A",first.check().checkNo()).occupancies()).hasSize(1);
            assertThat(s.getCheck("E","A",first.check().checkNo()).occupancies().getFirst().checkNo()).isEqualTo(first.check().checkNo());
        });
    }
    @Test void expandingExclusiveTableNeverAllowsSharing() {
        run(c -> {
            fixture(c);var t=table(c,"T1");var s=c.getBean(CheckService.class);
            s.openCheck("E","A","actor",open(t,2,"EXCLUSIVE"),"open");
            c.getBean(TableService.class).saveTable("E","A",t.tableNo(),"actor",new SaveTableReq("AREA1","加位",8,0,1L),"expand");
            DiningSessionTest.code(() -> s.openSharedCheck("E","A",t.tableNo(),"actor",new OpenSharedCheckReq(2L,1,"PREPAY"),"share"),ErrorCode.CONFLICT);
        });
    }
    @Test void listAndDetailAreScopedAndSeatsKeepStableIdentity() {
        run(c -> {
            var f=fixture(c);var t=table(c,"T1");var s=c.getBean(CheckService.class);
            var req=new OpenCheckReq(List.of(t.tableNo()),2,"PREPAY",null,List.of(new TableVersionReq(t.tableNo(),0L)),
                    "SHARED",List.of(new SeatReq(null,"1",null),new SeatReq(null,"2",null)),null);
            var check=s.openCheck("E","A","actor",req,"open").check();
            var before=s.getCheck("E","A",check.checkNo());assertThat(before.seats()).hasSize(2);
            var seat=before.seats().stream().filter(x -> x.label().equals("1")).findFirst().orElseThrow();
            assertThat(s.listChecks("E","A",null).records()).containsExactly(check);
            assertThat(s.listChecks("E","B",null).total()).isZero();
            DiningSessionTest.code(() -> s.getCheck("E","B",check.checkNo()),ErrorCode.NOT_FOUND);
            var changed=s.saveSeats("E","A",check.checkNo(),"actor",new SaveCheckSeatsReq(0L,1,List.of(seat),"离席"),"seats");
            assertThat(changed.check().version()).isEqualTo(1);
            assertThat(s.getCheck("E","A",check.checkNo()).seats()).containsExactly(seat);
            s.saveSeats("E","A",check.checkNo(),"actor",new SaveCheckSeatsReq(1L,2,before.seats(),"恢复"),"restore");
            assertThat(s.getCheck("E","A",check.checkNo()).seats()).containsExactlyElementsOf(before.seats());
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_seat",Integer.class)).isEqualTo(2);
            f.sql().update("INSERT INTO fnb_order_batch(entity_no,check_no) VALUES('E',?)",check.checkNo());
            DiningSessionTest.code(() -> s.saveSeats("E","A",check.checkNo(),"actor",new SaveCheckSeatsReq(2L,1,List.of(seat),"已成交"),"sold"),ErrorCode.CONFLICT);
        });
    }
    @Test void clearIsExplicitPreservesOtherGroupsAndRecordsRecoverableHistory() {
        run(c -> {
            var f=fixture(c);var t=table(c,"T1");var s=c.getBean(CheckService.class);
            var one=s.openCheck("E","A","actor",open(t,2,"SHARED"),"one").check();
            var two=s.openSharedCheck("E","A",t.tableNo(),"actor",new OpenSharedCheckReq(1L,2,"PREPAY"),"two").check();
            var occ=s.getCheck("E","A",one.checkNo()).occupancies().getFirst();
            var req=new ClearCheckReq(0L,"客人离店",null,List.of(occ.occupancyNo()));
            var result=s.clearCheck("E","A",one.checkNo(),"actor",req,"clear");
            assertThat(s.clearCheck("E","A",one.checkNo(),"actor",req,"clear")).isEqualTo(result);
            assertThat(s.getTableOperation("E","A",result.operationNo()).operation()).isEqualTo(result);
            assertThat(s.getCheck("E","A",one.checkNo()).check().status()).isEqualTo("VOIDED");
            assertThat(s.getCheck("E","A",two.checkNo()).occupancies()).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_occupancy",Integer.class)).isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT turnover_state FROM fnb_table",String.class)).isEqualTo("IN_USE");
            assertThat(s.listActions("E","A",one.checkNo(),null).records()).extracting(ActionLogVO::action).contains("OPEN_CHECK","CLEAR");
            DiningSessionTest.code(() -> s.getTableOperation("E","B",result.operationNo()),ErrorCode.NOT_FOUND);
            var otherOcc=s.getCheck("E","A",two.checkNo()).occupancies().getFirst();
            s.clearCheck("E","A",two.checkNo(),"actor",new ClearCheckReq(0L,"已清洁",null,List.of(otherOcc.occupancyNo())),"two-clear");
            assertThat(f.sql().queryForObject("SELECT turnover_state FROM fnb_table",String.class)).isEqualTo("IDLE");
        });
    }
    @Test void tradeFactsAndDraftsBlockClearAndTopologyWithoutMutatingHistory() {
        for(String fact:List.of("fnb_order_batch","fnb_check_batch_membership","fnb_settlement_item","fnb_return","fnb_dining_entitlement","fnb_check_cart")) {
            run(c -> {
                var f=fixture(c);var t=table(c,"T1");var target=table(c,"T2");var s=c.getBean(CheckService.class);
                var check=s.openCheck("E","A","actor",open(t,2,"EXCLUSIVE"),"open").check();
                var occ=s.getCheck("E","A",check.checkNo()).occupancies().getFirst();
                f.sql().update("INSERT INTO "+fact+"(entity_no,check_no"+(fact.equals("fnb_check_cart")?",status":"")+") VALUES('E',?"+(fact.equals("fnb_check_cart")?",'OPEN'":"")+")",check.checkNo());
                DiningSessionTest.code(() -> s.clearCheck("E","A",check.checkNo(),"actor",
                        new ClearCheckReq(0L,"离店",null,List.of(occ.occupancyNo())),"clear"),ErrorCode.CONFLICT);
                DiningSessionTest.code(() -> s.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,"换台",null,
                        List.of(target.tableNo()),List.of(new TableVersionReq(t.tableNo(),1L),new TableVersionReq(target.tableNo(),0L))),"move"),ErrorCode.CONFLICT);
                assertThat(s.getCheck("E","A",check.checkNo()).occupancies()).containsExactly(occ);
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_operation",Integer.class)).isZero();
            });
        }
    }
    @Test void transferAndCombineAreAtomicAndRecoverOriginalOperation() {
        run(c -> {
            var f=fixture(c);var first=table(c,"T1");var target=table(c,"T2");var third=table(c,"T3");var s=c.getBean(CheckService.class);
            var check=s.openCheck("E","A","actor",open(first,2,"EXCLUSIVE"),"open").check();
            var old=s.getCheck("E","A",check.checkNo()).occupancies().getFirst();
            var request=new TransferCheckReq(0L,"换到窗边",null,List.of(target.tableNo()),
                    List.of(new TableVersionReq(first.tableNo(),1L),new TableVersionReq(target.tableNo(),0L)));
            var move=s.transferCheck("E","A",check.checkNo(),"actor",request,"move");
            assertThat(s.transferCheck("E","A",check.checkNo(),"actor",request,"move")).isEqualTo(move);
            assertThat(s.getTableOperation("E","A",move.operation().operationNo())).isEqualTo(move);
            assertThat(s.getCheck("E","A",check.checkNo()).check().tableNos()).containsExactly(target.tableNo());
            assertThat(f.sql().queryForObject("SELECT ended_at FROM fnb_table_occupancy WHERE occupancy_no=?",Object.class,old.occupancyNo())).isNotNull();
            var combine=s.combineTables("E","A",check.checkNo(),"actor",new CombineTablesReq(1L,
                    List.of(new TableVersionReq(third.tableNo(),0L)),"增加一桌"),"combine");
            assertThat(s.getTableOperation("E","A",combine.operation().operationNo())).isEqualTo(combine);
            assertThat(s.getCheck("E","A",check.checkNo()).check().tableNos()).containsExactlyInAnyOrder(target.tableNo(),third.tableNo());
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_operation",Integer.class)).isEqualTo(2);
        });
    }
    @Test void occupiedOrStaleTargetNeverLosesSourceOccupancy() {
        run(c -> {
            var f=fixture(c);var t=table(c,"T1");var target=table(c,"T2");var s=c.getBean(CheckService.class);
            var check=s.openCheck("E","A","actor",open(t,2,"EXCLUSIVE"),"open").check();
            s.openCheck("E","A","actor",open(target,2,"EXCLUSIVE"),"target");
            var before=s.getCheck("E","A",check.checkNo());
            var req=new TransferCheckReq(0L,"换台",null,List.of(target.tableNo()),
                    List.of(new TableVersionReq(t.tableNo(),1L),new TableVersionReq(target.tableNo(),1L)));
            DiningSessionTest.code(() -> s.transferCheck("E","A",check.checkNo(),"actor",req,"move"),ErrorCode.CONFLICT);
            assertThat(s.getCheck("E","A",check.checkNo())).isEqualTo(before);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_operation",Integer.class)).isZero();
        });
    }
    @Test void noTableCanBeVoidedAndInvalidInputsNeverCreateReceipts() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(CheckService.class);var table=table(c,"T1");
            var fast=s.openCheck("E","A","actor",new OpenCheckReq(List.of(),1,"PREPAY",null,List.of(),"EXCLUSIVE",List.of(),null),"fast").check();
            s.clearCheck("E","A",fast.checkNo(),"actor",new ClearCheckReq(0L,"取消接待",null,List.of()),"void");
            assertThat(s.getCheck("E","A",fast.checkNo()).check().status()).isEqualTo("VOIDED");
            for(var req:List.of(new OpenCheckReq(List.of(table.tableNo()),1,"PREPAY",null,List.of(),"SHARED",List.of(),null),
                    new OpenCheckReq(List.of(table.tableNo(),table.tableNo()),1,"PREPAY",null,List.of(),"SHARED",List.of(),null),
                    new OpenCheckReq(List.of(),0,"PREPAY",null,List.of(),"EXCLUSIVE",List.of(),null),
                    new OpenCheckReq(List.of(),1,"PREPAY",null,List.of(),"OTHER",List.of(),null)))
                DiningSessionTest.code(() -> s.openCheck("E","A","actor",req,"invalid"),ErrorCode.BAD_REQUEST);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isOne();
        });
    }
    @Test void eachWriteRollsBackAfterActualOutboxFlush() {
        for(String action:List.of("open","seats","clear","transfer","combine","share")) {
            run(c -> {
                var f=fixture(c);var table=table(c,"T1");var target=table(c,"T2");var s=c.getBean(CheckService.class);
                CheckVO check=action.equals("open")?null:s.openCheck("E","A","actor",open(table,2,"SHARED"),"initial").check();
                var before=new LinkedHashMap<String,List<Map<String,Object>>>();
                for(String name:List.of("fnb_check","fnb_table","fnb_table_occupancy","fnb_seat","fnb_table_operation",
                        "fnb_action_log","sys_idempotent","sys_outbox")) before.put(name,f.sql().queryForList("SELECT * FROM "+name+" ORDER BY id"));
                c.getBean(StorePolicyPublicationTest.Signals.class).afterFlush.set(true);
                assertThatThrownBy(() -> {
                    switch(action) {
                        case "open" -> s.openCheck("E","A","actor",open(table,2,"SHARED"),"fail");
                        case "seats" -> s.saveSeats("E","A",check.checkNo(),"actor",new SaveCheckSeatsReq(0L,3,List.of(),"人数"),"fail");
                        case "clear" -> s.clearCheck("E","A",check.checkNo(),"actor",new ClearCheckReq(0L,"离店",null,
                                List.of(s.getCheck("E","A",check.checkNo()).occupancies().getFirst().occupancyNo())),"fail");
                        case "transfer" -> s.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,"换台",null,
                                List.of(target.tableNo()),List.of(new TableVersionReq(table.tableNo(),1L),new TableVersionReq(target.tableNo(),0L))),"fail");
                        case "combine" -> s.combineTables("E","A",check.checkNo(),"actor",new CombineTablesReq(0L,List.of(new TableVersionReq(target.tableNo(),0L)),"并桌"),"fail");
                        default -> s.openSharedCheck("E","A",table.tableNo(),"actor",new OpenSharedCheckReq(1L,1,"PREPAY"),"fail");
                    }
                }).isInstanceOf(IllegalStateException.class).hasMessage("after-check-event-flush");
                for(var entry:before.entrySet()) assertThat(f.sql().queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY id"))
                        .as(action+":"+entry.getKey()).isEqualTo(entry.getValue());
            });
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void concurrentExclusiveOpenHasOneWinner() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var s=c.getBean(CheckService.class);
            var winners=new DiningSessionTest().race(() -> s.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"left"),
                    () -> s.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"right"));
            assertThat(winners).filteredOn(CheckResultVO.class::isInstance).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_occupancy WHERE ended_at IS NULL",Integer.class)).isOne();
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void concurrentSharedLastSeatsNeverExceedCapacity() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var s=c.getBean(CheckService.class);
            s.openCheck("E","A","actor",open(table,2,"SHARED"),"initial");
            var result=new DiningSessionTest().race(() -> s.openSharedCheck("E","A",table.tableNo(),"actor",new OpenSharedCheckReq(1L,2,"PREPAY"),"left"),
                    () -> s.openSharedCheck("E","A",table.tableNo(),"actor",new OpenSharedCheckReq(1L,2,"PREPAY"),"right"));
            assertThat(result).filteredOn(CheckResultVO.class::isInstance).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT SUM(seats) FROM fnb_table_occupancy WHERE ended_at IS NULL",Integer.class)).isEqualTo(4);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void concurrentResizeAcrossChecksSharesOneCapacityArbiter() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var s=c.getBean(CheckService.class);
            var one=s.openCheck("E","A","actor",open(table,2,"SHARED"),"one").check();
            var two=s.openSharedCheck("E","A",table.tableNo(),"actor",new OpenSharedCheckReq(1L,1,"PREPAY"),"two").check();
            var result=new DiningSessionTest().race(() -> s.saveSeats("E","A",one.checkNo(),"actor",new SaveCheckSeatsReq(0L,3,List.of(),"加人"),"left"),
                    () -> s.saveSeats("E","A",two.checkNo(),"actor",new SaveCheckSeatsReq(0L,2,List.of(),"加人"),"right"));
            assertThat(result).filteredOn(CheckResultVO.class::isInstance).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT SUM(seats) FROM fnb_table_occupancy WHERE ended_at IS NULL",Integer.class)).isEqualTo(4);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void clearRacingTransferHasOneOperationAndKeepsConsistentOccupancy() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var target=table(c,"T2");var s=c.getBean(CheckService.class);
            var check=s.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"initial").check();
            var occ=s.getCheck("E","A",check.checkNo()).occupancies().getFirst();
            var result=new DiningSessionTest().race(() -> s.clearCheck("E","A",check.checkNo(),"actor",new ClearCheckReq(0L,"离店",null,List.of(occ.occupancyNo())),"clear"),
                    () -> s.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,"换台",null,List.of(target.tableNo()),
                            List.of(new TableVersionReq(table.tableNo(),1L),new TableVersionReq(target.tableNo(),0L))),"move"));
            assertThat(result).filteredOn(x -> x instanceof OperationVO || x instanceof OperationResultVO).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_operation",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_occupancy WHERE table_no=? AND ended_at IS NULL",Integer.class,table.tableNo())).isZero();
            assertThat(s.getCheck("E","A",check.checkNo()).check().version()).isEqualTo(1);
        });
    }
    @Test void unknownDraftStatusIsNotProofOfNoWork() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var service=c.getBean(CheckService.class);
            var check=service.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"open").check();
            var occupancy=service.getCheck("E","A",check.checkNo()).occupancies().getFirst();
            f.sql().update("INSERT INTO fnb_check_cart(entity_no,check_no,status) VALUES('E',?,NULL)",check.checkNo());
            DiningSessionTest.code(() -> service.clearCheck("E","A",check.checkNo(),"actor",
                    new ClearCheckReq(0L,"离店",null,List.of(occupancy.occupancyNo())),"clear"),ErrorCode.CONFLICT);
        });
    }
    @Test void untradedSeatCanBeRelabeledWithIdentityAndSnapshotHistoryPreserved() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var service=c.getBean(CheckService.class);
            var check=service.openCheck("E","A","actor",new OpenCheckReq(List.of(table.tableNo()),2,"PREPAY",null,
                    List.of(new TableVersionReq(table.tableNo(),0L)),"SHARED",List.of(new SeatReq(null,"1",null)),null),"open").check();
            var original=service.getCheck("E","A",check.checkNo()).seats().getFirst();
            service.saveSeats("E","A",check.checkNo(),"actor",new SaveCheckSeatsReq(0L,2,
                    List.of(new SeatReq(original.seatNo(),"靠窗",null)),"座位改名"),"rename");
            assertThat(service.getCheck("E","A",check.checkNo()).seats())
                    .containsExactly(new SeatReq(original.seatNo(),"靠窗",null));
            var payload=c.getBean(ObjectMapper.class).readTree(f.sql().queryForObject(
                    "SELECT payload FROM sys_outbox WHERE event_type='FOOD_CHECK_CHANGED' ORDER BY id DESC LIMIT 1",String.class));
            assertThat(payload.get("beforeSeats").get(0).get("label").asString()).isEqualTo("1");
            assertThat(payload.get("afterSeats").get(0).get("label").asString()).isEqualTo("靠窗");
        });
    }
    @Test void actionTimeAndBaseAuditFollowPlatformDefaultsInNonUtcJvm() {
        TimeZone previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        try {
            run(c -> {
                var f=fixture(c);var table=table(c,"T1");var service=c.getBean(CheckService.class);
                var before=Instant.now().minusSeconds(1);
                var check=service.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"open").check();
                var action=service.listActions("E","A",check.checkNo(),null).records().getFirst();
                assertThat(action.occurredAt()).isBetween(before,Instant.now().plusSeconds(1));
                assertThat(action.actorRef()).isEqualTo("actor");
                assertThat(f.sql().queryForObject("SELECT created_by FROM fnb_check",String.class)).isEqualTo("SYSTEM");
            });
        } finally { TimeZone.setDefault(previous); }
    }
    @Test void externalBindingsAndPendingOperationsNeverReleaseLocalResources() {
        for(String binding:List.of("table","claim","pending","target-pending","target-external")) run(c -> {
            var f=fixture(c);var table=table(c,"T1");var target=table(c,"T2");var service=c.getBean(CheckService.class);
            var check=service.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"open").check();
            var before=service.getCheck("E","A",check.checkNo());var occ=before.occupancies().getFirst();
            if(binding.equals("table")) f.sql().update("UPDATE fnb_table SET resource_no='EXTERNAL' WHERE table_no=?",table.tableNo());
            if(binding.equals("claim")) f.sql().update("UPDATE fnb_table_occupancy SET resource_claim_no='EXTERNAL' WHERE occupancy_no=?",occ.occupancyNo());
            if(binding.equals("pending")) f.sql().update("INSERT INTO fnb_table_operation(entity_no,store_no,check_no,operation_no,kind,request_key,request_hash,expected_versions,target_scope,status,reason,deleted)"
                    +" VALUES('E','A',?,'PENDING','TRANSFER','pending','hash','{}','{}','CLAIMING','pending',1)",check.checkNo());
            if(binding.startsWith("target-")) f.sql().update("INSERT INTO fnb_table_operation(entity_no,store_no,check_no,target_check_no,operation_no,kind,request_key,request_hash,expected_versions,target_scope,status,reason,resource_operation_ref,deleted)"
                    +" VALUES('E','A','OTHER',?,'TARGET','MERGE','target','hash','{}','{}',?,'target',?,1)",check.checkNo(),
                    binding.equals("target-pending")?"CLAIMING":"APPLIED",binding.equals("target-external")?"EXTERNAL":null);
            var error=binding.equals("pending")||binding.startsWith("target-")?ErrorCode.CONFLICT:ErrorCode.FORBIDDEN;
            DiningSessionTest.code(() -> service.clearCheck("E","A",check.checkNo(),"actor",new ClearCheckReq(0L,"离店",null,List.of(occ.occupancyNo())),"clear"),error);
            DiningSessionTest.code(() -> service.transferCheck("E","A",check.checkNo(),"actor",new TransferCheckReq(0L,"换台",null,
                    List.of(target.tableNo()),List.of(new TableVersionReq(table.tableNo(),1L),new TableVersionReq(target.tableNo(),0L))),"move"),error);
            DiningSessionTest.code(() -> service.saveSeats("E","A",check.checkNo(),"actor",new SaveCheckSeatsReq(0L,3,List.of(),"人数"),"seats"),error);
            DiningSessionTest.code(() -> service.combineTables("E","A",check.checkNo(),"actor",new CombineTablesReq(0L,
                    List.of(new TableVersionReq(target.tableNo(),0L)),"并桌"),"combine"),error);
            assertThat(service.getCheck("E","A",check.checkNo())).isEqualTo(before);
        });
    }
    @Test void pendingOperationQueryReturnsActualStateWithoutInventingAResultVersion() {
        run(c -> {
            var f=fixture(c);var table=table(c,"T1");var service=c.getBean(CheckService.class);
            var check=service.openCheck("E","A","actor",open(table,2,"EXCLUSIVE"),"open").check();
            f.sql().update("INSERT INTO fnb_table_operation(entity_no,store_no,check_no,operation_no,kind,request_key,request_hash,expected_versions,target_scope,status,reason)"
                    +" VALUES('E','A',?,'PENDING','TRANSFER','pending','hash','{}','{}','CLAIMING','pending')",check.checkNo());
            var result=service.getTableOperation("E","A","PENDING").operation();
            assertThat(result.status()).isEqualTo("CLAIMING");assertThat(result.version()).isZero();
            assertThat(result.resultRef()).isEqualTo(check.checkNo());
        });
    }
    @Test void partialClearKeepsRemainingSharedCapacityEqualToParty() {
        run(c -> {
            fixture(c);var a=table(c,"T1");var b=table(c,"T2");var service=c.getBean(CheckService.class);
            var check=service.openCheck("E","A","actor",new OpenCheckReq(List.of(a.tableNo(),b.tableNo()),4,"PREPAY",null,
                    List.of(new TableVersionReq(a.tableNo(),0L),new TableVersionReq(b.tableNo(),0L)),"SHARED",List.of(),null),"open").check();
            var old=service.getCheck("E","A",check.checkNo()).occupancies();
            service.clearCheck("E","A",check.checkNo(),"actor",new ClearCheckReq(0L,"释放一桌",null,List.of(old.getFirst().occupancyNo())),"clear");
            var remaining=service.getCheck("E","A",check.checkNo());
            assertThat(remaining.check().status()).isEqualTo("OPEN");assertThat(remaining.occupancies()).hasSize(1);
            assertThat(remaining.occupancies().getFirst().seats()).isEqualTo(4);
        });
    }
    @Test void partialClearRefusesInsufficientRemainingCapacityAndKeepsAllOccupancy() {
        run(c -> {
            fixture(c);var a=table(c,"T1");var b=table(c,"T2");var service=c.getBean(CheckService.class);
            var check=service.openCheck("E","A","actor",new OpenCheckReq(List.of(a.tableNo(),b.tableNo()),6,"PREPAY",null,
                    List.of(new TableVersionReq(a.tableNo(),0L),new TableVersionReq(b.tableNo(),0L)),"EXCLUSIVE",List.of(),null),"open").check();
            var before=service.getCheck("E","A",check.checkNo());
            DiningSessionTest.code(() -> service.clearCheck("E","A",check.checkNo(),"actor",new ClearCheckReq(0L,"释放一桌",null,
                    List.of(before.occupancies().getFirst().occupancyNo())),"clear"),ErrorCode.CONFLICT);
            assertThat(service.getCheck("E","A",check.checkNo())).isEqualTo(before);
        });
    }
    @Configuration(proxyBeanMethods=false) static class Checks {
        @Bean
        MapperFactoryBean<CheckMapper> checkMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m=new MapperFactoryBean<>(CheckMapper.class);
            m.setSqlSessionTemplate(t);return m;
        }
        @Bean
        MapperFactoryBean<TableOccupancyMapper> tableOccupancyMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m=new MapperFactoryBean<>(TableOccupancyMapper.class);
            m.setSqlSessionTemplate(t);return m;
        }
        @Bean
        MapperFactoryBean<SeatMapper> seatMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m=new MapperFactoryBean<>(SeatMapper.class);
            m.setSqlSessionTemplate(t);return m;
        }
        @Bean
        MapperFactoryBean<TableOperationMapper> tableOperationMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m=new MapperFactoryBean<>(TableOperationMapper.class);
            m.setSqlSessionTemplate(t);return m;
        }
        @Bean
        CheckService checkService(CheckMapper checks,
                TableAreaMapper areas,
                TableMapper tables,
                TableOccupancyMapper occupancy,
                SeatMapper seats,
                TableOperationMapper operations,
                GuestSessionMapper sessions,
                ActionLogMapper actions,
                StoreOperationService policies,IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher pub,
                ObjectMapper json,PlatformTransactionManager manager,
                StorePolicyPublicationTest.Signals signals,DataSource source) {
            OutboxPublisher checked=(event,options) -> {
                int before=signals.afterFlush.get() ? new JdbcTemplate(source)
                        .queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class):0;
                var result=pub.publish(event,options);
                if(signals.afterFlush.get()) TransactionSynchronizationManager
                        .registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                assertThat(new JdbcTemplate(source)
                                        .queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isEqualTo(before+1);
                                throw new IllegalStateException("after-check-event-flush");
                            }
                        });
                return result;
            };
            return new CheckServiceImpl(checks,areas,tables,occupancy,seats,operations,
                    sessions,actions,policies,idem,checked,json,manager);
        }
    }
}
