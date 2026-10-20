package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.service.TableService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import tools.jackson.databind.ObjectMapper;

class TableConfigurationTest extends FoodWriteGuardFixture {
    private Fixture fixture(AssertableApplicationContext c) {
        var f = prepare(c);
        StorePolicyPublicationTest.createPolicyTables(f);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        String binary = "true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))
                ? " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : "";
        f.sql().execute("CREATE TABLE fnb_table_area ("+common+"area_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),"
                + "code VARCHAR(32)"+binary+",name VARCHAR(64),sort_no INT,status VARCHAR(16),UNIQUE(store_no,code))");
        f.sql().execute("CREATE TABLE fnb_table ("+common+"table_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),"
                + "area_no VARCHAR(32),code VARCHAR(32)"+binary+",name VARCHAR(64),capacity INT,sort_no INT,"
                + "resource_no VARCHAR(32),status VARCHAR(16),turnover_state VARCHAR(16),UNIQUE(store_no,code))");
        f.sql().execute("CREATE TABLE fnb_table_occupancy ("+common+"occupancy_no VARCHAR(32),table_no VARCHAR(32),"
                + "ended_at DATETIME(3))");
        f.sql().update("INSERT INTO fnb_table_area(entity_no,store_no,area_no,code,name,sort_no,status) VALUES"
                + "('E','A','AREA1','H','大厅',0,'ACTIVE'),('E','A','AREA2','R','包间',1,'ACTIVE'),"
                + "('E','B','FOREIGN','F','外店',0,'ACTIVE'),('OTHER','C','OTHER','O','外主体',0,'ACTIVE')");
        return f;
    }
    private void run(ContextConsumer<AssertableApplicationContext> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,TableServiceTest.Areas.class)
                .run(test);
    }
    private TableVO create(TableService s,String code) {
        var result=s.createTable("E","A","actor",req("AREA1",code),code);
        assertThat(result).isNotNull();
        return result.table();
    }
    private CreateTableReq req(String area,String code) { return new CreateTableReq(area,code,"四人桌",4,0,null); }
    private SetStatusTableReq state(String status,long version) {
        return new SetStatusTableReq(status,version," 调整 ");
    }
    @Test void createsAndReplays() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);var first=create(s,"T1");
            assertThat(first.status()).isEqualTo("ACTIVE");assertThat(first.turnoverState()).isEqualTo("IDLE");
            assertThat(first.version()).isZero();assertThat(first.resourceNo()).isNull();
            assertThat(s.createTable("E","A","actor",req("AREA1"," T1 "),"T1").table()).isEqualTo(first);
            code(() -> s.createTable("E","A","actor",req("AREA1","T1"),"duplicate"),ErrorCode.CONFLICT);
            code(() -> s.createTable("E","A","actor",req("AREA1","T2"),"T1"),ErrorCode.CONFLICT);
            code(() -> s.createTable("E","A","other",req("AREA1","T1"),"T1"),ErrorCode.NOT_FOUND);
            assertThat(s.createTable("E","B","actor",req("FOREIGN","T1"),"T1")).isNotNull();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table",Integer.class)).isEqualTo(2);
        });
    }
    @Test void readsOnlyScopedTables() {
        run(c -> {
            fixture(c);var s=c.getBean(TableService.class);
            assertThat(s.listTables("E","A",null)).isNotNull();
            assertThat(s.listTables("E","A",null).total()).isZero();
            var a=create(s,"T1");var b=s.createTable("E","A","actor",req("AREA2","T2"),"T2").table();
            s.createTable("E","B","actor",req("FOREIGN","T1"),"T1");
            var page=s.listTables("E","A",new ListTablesQuery(1,1,null,null,null,null));
            assertThat(page.total()).isEqualTo(2);assertThat(page.records()).hasSize(1);
            assertThat(s.listTables("E","A",new ListTablesQuery(1,20,"AREA2",null,"IDLE",null)).records())
                    .containsExactly(b);
            assertThat(s.getTable("E","A",a.tableNo()).table()).isEqualTo(a);
            code(() -> s.getTable("E","B",a.tableNo()),ErrorCode.NOT_FOUND);
            code(() -> s.getTable("OTHER","A",a.tableNo()),ErrorCode.NOT_FOUND);
            var expected=List.of(a.tableNo(),b.tableNo()).stream().sorted().toList();
            assertThat(s.listTables("E","A",null).records()).extracting(TableVO::tableNo)
                    .containsExactlyElementsOf(expected);
        });
    }
    @Test void keywordIsLiteral() {
        run(c -> {
            fixture(c);var s=c.getBean(TableService.class);var special=create(s,"T_%!");create(s,"TABC");
            for(String keyword:List.of("%","_","!")) {
                assertThat(s.listTables("E","A",new ListTablesQuery(null,null,null,null,null,keyword)).records())
                        .containsExactly(special);
            }
            assertThat(s.listTables("E","A",new ListTablesQuery(null,null,null,null,null,"' OR 1=1 --")).total())
                    .isZero();
        });
    }
    @Test void editsWithVersionAndRejectsForeignMutation() {
        run(c -> {
            fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            var edit=new SaveTableReq("AREA2"," 六人桌 ",6,3,a.version());
            var changed=s.saveTable("E","A",a.tableNo(),"actor",edit,"edit");
            assertThat(changed.table().name()).isEqualTo("六人桌");
            assertThat(changed.table().areaNo()).isEqualTo("AREA2");assertThat(changed.table().version()).isOne();
            assertThat(changed.table().code()).isEqualTo("T1");
            assertThat(s.saveTable("E","A",a.tableNo(),"actor",edit,"edit")).isEqualTo(changed);
            code(() -> s.saveTable("E","A",a.tableNo(),"actor",edit,"old"),ErrorCode.CONFLICT);
            code(() -> s.saveTable("E","B",a.tableNo(),"actor",edit,"other"),ErrorCode.NOT_FOUND);
            code(() -> s.setStatusTable("E","B",a.tableNo(),"actor",state("DISABLED",1),"other"),ErrorCode.NOT_FOUND);
            code(() -> s.archiveTable("OTHER","A",a.tableNo(),"actor",new ArchiveTableReq(1L,"归档"),"x"),
                    ErrorCode.NOT_FOUND);
            var second=create(s,"T2");
            code(() -> s.saveTable("E","A",second.tableNo(),"actor",edit,"edit"),ErrorCode.CONFLICT);
        });
    }
    @Test void occupancyBlocksUnsafeChanges() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            f.sql().update("INSERT INTO fnb_table_occupancy(entity_no,table_no,occupancy_no) VALUES('E',?,'O1')",a.tableNo());
            code(() -> s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA2","移区",4,0,0L),"move"),
                    ErrorCode.CONFLICT);
            code(() -> s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA1","缩容",2,0,0L),"shrink"),
                    ErrorCode.CONFLICT);
            code(() -> s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",0),"off"),ErrorCode.CONFLICT);
            var renamed=s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA1","改名",4,8,0L),"rename");
            assertThat(renamed.table().name()).isEqualTo("改名");
            f.sql().update("UPDATE fnb_table SET status='DISABLED' WHERE table_no=?",a.tableNo());
            code(() -> s.archiveTable("E","A",a.tableNo(),"actor",new ArchiveTableReq(1L,"归档"),"archive"),
                    ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT ended_at FROM fnb_table_occupancy",Object.class)).isNull();
        });
    }
    @Test void rejectsDisabledAndForeignAreas() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            for(String area:List.of("FOREIGN","OTHER","missing")) {
                code(() -> s.createTable("E","A","actor",req(area,"OTHER"),"create"),ErrorCode.NOT_FOUND);
                code(() -> s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq(area,"x",4,0,0L),"save"),
                        ErrorCode.NOT_FOUND);
            }
            f.sql().update("UPDATE fnb_table_area SET status='DISABLED' WHERE area_no='AREA2'");
            code(() -> s.createTable("E","A","actor",req("AREA2","T2"),"create"),ErrorCode.CONFLICT);
            code(() -> s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA2","x",4,0,0L),"move"),
                    ErrorCode.CONFLICT);
            var off=s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",0),"off");
            f.sql().update("UPDATE fnb_table_area SET status='DISABLED' WHERE area_no='AREA1'");
            code(() -> s.setStatusTable("E","A",a.tableNo(),"actor",state("ACTIVE",off.table().version()),"on"),
                    ErrorCode.CONFLICT);
        });
    }
    @Test void statusAndArchiveKeepHistory() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            code(() -> s.archiveTable("E","A",a.tableNo(),"actor",new ArchiveTableReq(0L,"归档"),"early"),
                    ErrorCode.CONFLICT);
            assertThat(s.setStatusTable("E","A",a.tableNo(),"actor",state("ACTIVE",0),"noop").table()).isEqualTo(a);
            var off=s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",0),"off");
            code(() -> s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",0),"stale"),ErrorCode.CONFLICT);
            var archived=s.archiveTable("E","A",a.tableNo(),"actor",new ArchiveTableReq(off.table().version(),"归档"),"ar");
            assertThat(archived.operation().version()).isEqualTo(2);
            assertThat(archived.operation().resultRef()).isEqualTo(a.tableNo());
            assertThat(s.archiveTable("E","A",a.tableNo(),"actor",new ArchiveTableReq(1L,"归档"),"ar"))
                    .isEqualTo(archived);
            code(() -> s.getTable("E","A",a.tableNo()),ErrorCode.NOT_FOUND);
            assertThat(s.listTables("E","A",null).total()).isZero();
            assertThat(f.sql().queryForObject("SELECT deleted FROM fnb_table",Integer.class)).isOne();
            code(() -> s.createTable("E","A","actor",req("AREA1","T1"),"reuse"),ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isEqualTo(3);
        });
    }
    @Test void preservesAuditSnapshots() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA2","六人桌",6,2,0L),"edit");
            s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",1),"off");
            s.archiveTable("E","A",a.tableNo(),"actor",new ArchiveTableReq(2L,"归档原因"),"archive");
            var json=c.getBean(ObjectMapper.class);
            var events=f.sql().queryForList("SELECT payload FROM sys_outbox ORDER BY id",String.class)
                    .stream().map(json::readTree).toList();
            assertThat(events).hasSize(4);assertThat(events.get(0).get("before").isNull()).isTrue();
            assertThat(events.get(1).get("before").get("capacity").asInt()).isEqualTo(4);
            assertThat(events.get(1).get("after").get("areaNo").asString()).isEqualTo("AREA2");
            assertThat(events.get(3).get("action").asString()).isEqualTo("ARCHIVE_TABLE");
            assertThat(events.get(3).get("reason").asString()).isEqualTo("归档原因");
            for(var e:events) assertThat(f.sql().queryForObject("SELECT operator_no FROM fnb_action_log WHERE action_no=?",
                    String.class,e.get("operationNo").asString())).isEqualTo("actor");
        });
    }
    @Test void rejectsFutureVersionAcrossDiscoveryStatusChange() {
        run(c -> {
            var f=fixture(c);var service=c.getBean(TableService.class);var table=create(service,"T1");
            Runnable concurrentChanges=() -> {
                try(var worker=Executors.newSingleThreadExecutor()) {
                    worker.submit(() -> {
                        service.setStatusTable("E","A",table.tableNo(),"actor",state("DISABLED",0),"other-off");
                        service.setStatusTableArea("E","A","AREA1","actor",
                                new SetStatusTableAreaReq("DISABLED",0L,"关闭"),"other-area-off");
                    }).get(10,TimeUnit.SECONDS);
                } catch(Exception failure) { throw new IllegalStateException(failure); }
            };
            c.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(new AfterTableDiscovery(concurrentChanges));
            code(() -> service.setStatusTable("E","A",table.tableNo(),"actor",state("ACTIVE",1),"future-on"),
                    ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_table",String.class)).isEqualTo("DISABLED");
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_table_area WHERE area_no='AREA1'",String.class))
                    .isEqualTo("DISABLED");
        });
    }
    @Intercepts(@Signature(type=Executor.class,method="query",
            args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class AfterTableDiscovery implements Interceptor {
        private final AtomicBoolean armed=new AtomicBoolean(true);
        private final Runnable changes;
        AfterTableDiscovery(Runnable changes) { this.changes=changes; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result=invocation.proceed();
            var statement=(MappedStatement)invocation.getArgs()[0];
            if(statement.getId().endsWith("TableMapper.selectList") && armed.compareAndSet(true,false)) changes.run();
            return result;
        }
    }
    @Test void rejectsInvalidCommands() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);
            for(var r:List.of(new CreateTableReq("AREA1","T","x",0,0,null),
                    new CreateTableReq("AREA1"," ","x",4,0,null),
                    new CreateTableReq("AREA1","T","x",4,null,null))) {
                code(() -> s.createTable("E","A","actor",r,"k"),ErrorCode.BAD_REQUEST);
            }
            code(() -> s.createTable("E","A","actor",new CreateTableReq("AREA1","T","x",4,0,"UNKNOWN"),"k"),
                    ErrorCode.FORBIDDEN);
            code(() -> s.listTables("E","A",new ListTablesQuery(1,101,null,null,null,null)),ErrorCode.BAD_REQUEST);
            code(() -> s.listTables("E","A",new ListTablesQuery(1,20,null,null,"PAID",null)),ErrorCode.BAD_REQUEST);
            code(() -> s.createTable("E","A","actor",req("AREA1","T")," "),ErrorCode.BAD_REQUEST);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table",Integer.class)).isZero();
        });
    }
    @Test void rollbackAfterFlush() {
        run(c -> {
            var f=fixture(c);c.getBean(StorePolicyPublicationTest.Signals.class).afterFlush.set(true);
            assertThatThrownBy(() -> create(c.getBean(TableService.class),"T1"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("after-policy-event-flush");
            for(String t:List.of("fnb_table","fnb_action_log","sys_outbox","sys_idempotent"))
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM "+t,Integer.class)).as(t).isZero();
        });
    }
    @Test void failedEditStatusAndArchiveRollBackFlushedEvent() {
        for(String action:List.of("edit","status","archive")) {
            run(c -> {
                var f=fixture(c);var service=c.getBean(TableService.class);var row=create(service,"T1");
                if(action.equals("archive")) row=service.setStatusTable("E","A",row.tableNo(),"actor",state("DISABLED",0),"off").table();
                var before=row;int events=f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class);
                c.getBean(StorePolicyPublicationTest.Signals.class).afterFlush.set(true);
                assertThatThrownBy(() -> {
                    switch(action) {
                        case "edit" -> service.saveTable("E","A",before.tableNo(),"actor",new SaveTableReq("AREA2","changed",6,2,before.version()),"fail");
                        case "status" -> service.setStatusTable("E","A",before.tableNo(),"actor",state("DISABLED",before.version()),"fail");
                        default -> service.archiveTable("E","A",before.tableNo(),"actor",new ArchiveTableReq(before.version(),"archive"),"fail");
                    }
                }).isInstanceOf(IllegalStateException.class).hasMessage("after-policy-event-flush");
                assertThat(service.getTable("E","A",before.tableNo()).table()).isEqualTo(before);
                for(String table:List.of("sys_outbox","sys_idempotent","fnb_action_log"))
                    assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(events);
            });
        }
    }
    @Test void missingSourceDoesNotLeaveTableOrReceipt() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,TableServiceTest.Areas.class)
                .withPropertyValues("test.policy.source=false").run(c -> {
                    var f=fixture(c);
                    assertThatThrownBy(() -> create(c.getBean(TableService.class),"T1")).isInstanceOf(IllegalStateException.class);
                    for(String table:List.of("fnb_table","fnb_action_log","sys_outbox","sys_idempotent"))
                        assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
                });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void concurrentCodeCreation() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);
            var result=race(() -> s.createTable("E","A","actor",req("AREA1","T1"),"left"),
                    () -> s.createTable("E","A","actor",req("AREA1","T1"),"right"));
            assertThat(result).filteredOn(TableResultVO.class::isInstance).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table",Integer.class)).isOne();
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void raceCreateAgainstAreaDisable() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);
            var winners=race(() -> s.createTable("E","A","actor",req("AREA1","T1"),"create"),
                    () -> s.setStatusTableArea("E","A","AREA1","actor",new SetStatusTableAreaReq("DISABLED",0L,"关闭"),"off"));
            assertThat(winners).filteredOn(r -> r instanceof TableResultVO || r instanceof TableAreaResultVO).hasSize(1);
            assertNoActiveTableUnderDisabledArea(f);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void raceEnableAgainstAreaDisable() {
        run(c -> {
            var f=fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",0),"off-table");
            var winners=race(() -> s.setStatusTable("E","A",a.tableNo(),"actor",state("ACTIVE",1),"on"),
                    () -> s.setStatusTableArea("E","A","AREA1","actor",new SetStatusTableAreaReq("DISABLED",0L,"关闭"),"off-area"));
            assertThat(winners).filteredOn(r -> r instanceof TableResultVO || r instanceof TableAreaResultVO).hasSize(1);
            assertNoActiveTableUnderDisabledArea(f);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void raceSameVersion() {
        run(c -> {
            fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            var results=race(() -> s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA1","x",4,0,0L),"save"),
                    () -> s.setStatusTable("E","A",a.tableNo(),"actor",state("DISABLED",0),"off"));
            assertThat(results).filteredOn(TableResultVO.class::isInstance).hasSize(1);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void oppositeMovesShareStableAreaLockOrder() {
        run(c -> {
            fixture(c);var s=c.getBean(TableService.class);var a=create(s,"T1");
            var b=s.createTable("E","A","actor",req("AREA2","T2"),"T2").table();
            var results=race(() -> s.saveTable("E","A",a.tableNo(),"actor",new SaveTableReq("AREA2","x",4,0,0L),"a"),
                    () -> s.saveTable("E","A",b.tableNo(),"actor",new SaveTableReq("AREA1","y",4,0,0L),"b"));
            assertThat(results).allMatch(TableResultVO.class::isInstance);
        });
    }
    private void assertNoActiveTableUnderDisabledArea(Fixture f) {
        assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table t JOIN fnb_table_area a ON t.area_no=a.area_no"
                + " WHERE t.deleted=0 AND t.status='ACTIVE' AND a.status='DISABLED'",Integer.class)).isZero();
        assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isGreaterThan(0);
    }
    private List<Object> race(Callable<?> left,Callable<?> right) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> attempt(barrier,left));var b=pool.submit(() -> attempt(barrier,right));
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }
    private Object attempt(CyclicBarrier barrier,Callable<?> call) throws Exception {
        barrier.await(10,TimeUnit.SECONDS);
        try { return call.call(); } catch(BizException e) {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT);return e.errorCode();
        }
    }
    private void code(Runnable r,ErrorCode expected) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
