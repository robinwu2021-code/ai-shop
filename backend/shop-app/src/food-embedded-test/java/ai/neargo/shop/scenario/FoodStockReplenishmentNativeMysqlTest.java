package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.port.FoodLocalStockPortImpl;
import ai.neargo.shop.food.service.impl.FoodStockManagementServiceImpl;
import ai.neargo.shop.food.dto.FoodStockContracts.Adjust;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.ConnectionChange;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.food.port.FoodStockReplenishmentPortImpl;
import ai.neargo.shop.food.service.support.FoodStockSyncPublisher;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodLocalStockPort.*;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.Mapping;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Isolated real FOOD migrations/row locks/commands; no remote posting is replaced by a local adjustment. */
class FoodStockReplenishmentNativeMysqlTest {
    static final Scope S=new Scope("E","S");
    static BigDecimal q(String n){return new BigDecimal(n);}
    static Outbound source(){return new Outbound("INV_TEST","OWNER","OUT",1,"I","G",q("20"),"S",20);}
    static Mapping mapping(){return new Mapping("RAW","G",BigDecimal.ONE);}
    @Test void globalSourceLookupRetainsArchivedLegacyIdentityAndRejectsOtherScope()throws Exception {
        run((c,db)->{
            var port=c.getBean(FoodStockReplenishmentPort.class);var original=port.register(S,"A","original",source(),mapping());
            assertThat(port.findSource(S,"INV_TEST","OWNER","OUT",1)).usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo,BigDecimal.class).isEqualTo(original);
            db.update("UPDATE fnb_stock_replenishment SET deleted=1");
            assertThat(port.findSource(S,"INV_TEST","OWNER","OUT",1)).usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo,BigDecimal.class).isEqualTo(original);
            assertThatThrownBy(()->port.findSource(new Scope("OTHER","S"),"INV_TEST","OWNER","OUT",1)).isInstanceOf(BizException.class);
            assertThat(port.findSource(S,"INV_TEST","OWNER","ABSENT",1)).isNull();
            assertThat(onHand(db)).isZero();
        });
    }
    @Test void claimedFoodRegistrationRejectsWrongBindingBeforeAnyLocalEffect()throws Exception {
        run((c,db)->{
            var port=c.getBean(FoodStockReplenishmentPort.class);
            var claim=new ai.neargo.shop.spi.product.StockReplenishmentClaim("SRP","PLATFORM","wrong");
            assertThatThrownBy(()->port.registerClaimed(S,"A","bad",source(),mapping(),claim)).isInstanceOf(BizException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM fnb_stock_replenishment",Integer.class)).isZero();
            assertThat(onHand(db)).isZero();
        });
    }
    void run(BiConsumer<ApplicationContext,JdbcTemplate> work)throws Exception {
        assumeTrue("true".equals(System.getenv("I04B_NATIVE_MYSQL")));
        String url=System.getenv("I04B_MYSQL_URL"),user=System.getenv("I04B_MYSQL_USER"),pass=System.getenv("I04B_MYSQL_PASSWORD");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3308/?");
        String schema="i04b_"+UUID.randomUUID().toString().replace("-","");
        try(var conn=DriverManager.getConnection(url,user,pass);var stmt=conn.createStatement()){
            stmt.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            try {
                new ApplicationContextRunner().withUserConfiguration(Fixture.class).withPropertyValues(
                    "spring.datasource.url="+url.replace("/?","/"+schema+"?"),"spring.datasource.username="+user,
                    "spring.datasource.password="+pass).run(c->{
                    assertThat(c).hasNotFailed();var db=new JdbcTemplate(c.getBean(DataSource.class));
                    try(var connection=c.getBean(DataSource.class).getConnection()){
                        Path migrations=Path.of("../../backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food");
                        for(String migration:List.of("V33__restaurant_local_stock_foundation.sql","V34__food_local_stock_lifecycle.sql",
                                "V35__food_stock_optional_sync.sql","V36__food_stock_frozen_tracking.sql","V38__food_stock_replenishment.sql","V39__food_stock_replenishment_recovery.sql"))
                            ScriptUtils.executeSqlScript(connection,new FileSystemResource(migrations.resolve(migration)));
                    }catch(Exception e){throw new RuntimeException(e);}
                    db.update("INSERT INTO fnb_stock_item(tenant_no,entity_no,item_no,item_kind,name,base_unit,tracking_mode,status,created_at,created_by,updated_at,updated_by) VALUES('MAIN','E','RAW','MATERIAL','raw','G','TRACKED','ACTIVE',NOW(),'A',NOW(),'A')");
                    db.update("INSERT INTO fnb_stock_balance(tenant_no,entity_no,store_no,balance_no,item_no,tracking_mode,status,on_hand,reserved_qty,created_at,created_by,updated_at,updated_by) VALUES('MAIN','E','S','B','RAW','TRACKED','ACTIVE',0,0,NOW(),'A',NOW(),'A')");
                    db.update("INSERT INTO fnb_stock_replenishment_connection(tenant_no,entity_no,store_no,status,generation,source_system,source_owner,posting_watermark,created_at,created_by,updated_at,updated_by) VALUES('MAIN','E','S','ACTIVE',1,'INV_TEST','OWNER',0,NOW(),'A',NOW(),'A')");
                    work.accept(c,db);
                });
            }finally{stmt.execute("DROP DATABASE "+schema);}
        }
    }
    static BigDecimal onHand(JdbcTemplate db){return db.queryForObject("SELECT on_hand FROM fnb_stock_balance",BigDecimal.class);}
    @Test void receivesCumulativeOnceThenProductionOnlyConsumesLocal()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);var pending=p.register(S,"A","register",source(),mapping());
            assertThat(onHand(db)).isZero();assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(p.register(S,"A","other-generation",source(),mapping()).replenishmentNo()).isEqualTo(pending.replenishmentNo());
            String no=pending.replenishmentNo();var partial=p.receive(S,"A","receive-18",no,q("18"));
            assertThat(partial.status()).isEqualTo("PARTIAL");assertThat(onHand(db)).isEqualByComparingTo("18");
            assertThat(p.receive(S,"A","receive-18",no,q("18"))).isEqualTo(partial);
            p.receive(S,"A","different-key",no,q("18"));assertThat(onHand(db)).isEqualByComparingTo("18");
            assertThatThrownBy(()->p.receive(S,"A","overflow",no,q("21"))).isInstanceOf(BizException.class);
            var full=p.receive(S,"A","receive-20",no,q("20"));assertThat(full.status()).isEqualTo("RECEIVED");
            assertThat(p.findCommand(S,"REPLENISH_RECEIVE","receive-18")).isEqualTo(partial);
            assertThat(p.receive(S,"A","receive-18",no,q("18"))).isEqualTo(partial);
            assertThat(onHand(db)).isEqualByComparingTo("20");
            var local=c.getBean(FoodLocalStockPort.class);var json=JsonMapper.builder().build();
            String definition="{}";var leaf=new Leaf("L","C","RAW","G",q("3"),"PRODUCTION",definition,FoodStockFingerprint.sha256(definition));
            var rawPlan=new Plan("",null,List.of(leaf));var plan=new Plan(FoodStockFingerprint.plan(json,rawPlan),null,rawPlan.leaves());
            var raw=new Command("reserve","","CORE_ORDER","ORDER",0,0);
            var held=local.reserve(S,new Command("reserve",FoodStockFingerprint.request(json,"RESERVE",raw,List.of(plan)),"CORE_ORDER","ORDER",0,0),plan);
            var confirm=new Command("confirm","","CORE_ORDER","ORDER",0,held.lifecycleVersion());
            var accepted=local.confirm(S,new Command("confirm",FoodStockFingerprint.request(json,"CONFIRM",confirm,List.of(held.reservationNo())),"CORE_ORDER","ORDER",0,held.lifecycleVersion()),held.reservationNo());
            var targets=List.of(new Target("L",q("3")));var issue=new Command("issue","","CORE_ORDER","ORDER",0,accepted.lifecycleVersion());
            local.issue(S,new Command("issue",FoodStockFingerprint.request(json,"ISSUE",issue,List.of(held.reservationNo(),targets)),"CORE_ORDER","ORDER",0,accepted.lifecycleVersion()),held.reservationNo(),targets);
            assertThat(onHand(db)).isEqualByComparingTo("17");
            assertThat(db.queryForList("SHOW TABLES LIKE 'inv_%'")).isEmpty();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM fnb_stock_movement WHERE action='REPLENISH_RECEIVE'",Integer.class)).isEqualTo(2);
        });
    }
    @Test void freezesMappingScopeAndExactUnitsAndClosesShortage()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);var r=p.register(S,"A","register",source(),mapping());
            assertThatThrownBy(()->p.register(S,"A","remap",source(),new Mapping("RAW","G",q("2")))).isInstanceOf(BizException.class);
            assertThatThrownBy(()->p.register(new Scope("E","OTHER"),"A","other-store",source(),mapping())).isInstanceOf(BizException.class);
            assertThatThrownBy(()->p.get(new Scope("OTHER","S"),r.replenishmentNo())).isInstanceOf(BizException.class);
            p.receive(S,"A","receive",r.replenishmentNo(),q("18"));var closed=p.close(S,"A","close",r.replenishmentNo(),"SHORT");
            assertThat(closed.differenceQuantity()).isEqualByComparingTo("2");assertThat(closed.status()).isEqualTo("CLOSED");
            assertThatThrownBy(()->p.receive(S,"A","late",r.replenishmentNo(),q("20"))).isInstanceOf(BizException.class);
            assertThatThrownBy(()->p.receive(S,"A","receive",r.replenishmentNo(),q("19"))).isInstanceOf(BizException.class);
            assertThat(onHand(db)).isEqualByComparingTo("18");
            var converted=new Outbound("INV_TEST","OWNER","OUT",2,"PACK","KG",q("0.125"),"S",21);
            var small=p.register(S,"A","convert",converted,new Mapping("RAW","G",q("1000")));
            assertThat(small.expectedQuantity()).isEqualByComparingTo("125");
            p.receive(S,"A","small",small.replenishmentNo(),q("0.000001"));assertThat(onHand(db)).isEqualByComparingTo("18.000001");
            db.update("UPDATE fnb_stock_balance SET tracking_mode='UNTRACKED'");
            assertThatThrownBy(()->p.receive(S,"A","untracked",small.replenishmentNo(),q("1"))).isInstanceOf(BizException.class);
        });
    }
    @Test void returnRequiresFreeLocalQuantityAndPostedOriginalSourceReceipt()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);String no=p.register(S,"A","register",source(),mapping()).replenishmentNo();
            p.receive(S,"A","receive",no,q("20"));db.update("UPDATE fnb_stock_balance SET reserved_qty=19");
            assertThatThrownBy(()->p.dispatchReturn(S,"A","blocked",no,q("2"),"RETURN")).isInstanceOf(BizException.class);
            assertThat(onHand(db)).isEqualByComparingTo("20");db.update("UPDATE fnb_stock_balance SET reserved_qty=0");
            p.dispatchReturn(S,"A","send",no,q("3"),"RETURN");p.dispatchReturn(S,"A","send-again",no,q("3"),"RETURN");
            assertThat(onHand(db)).isEqualByComparingTo("17");assertThat(p.get(S,no).returnAcceptedQuantity()).isZero();
            var accepted=new Inbound("INV_TEST","OWNER","IN",1,"I","G",q("2"),no);
            p.ackReturn(S,"A","ack",no,accepted);p.ackReturn(S,"A","ack-again",no,accepted);
            assertThat(p.get(S,no).returnAcceptedQuantity()).isEqualByComparingTo("2");
            assertThatThrownBy(()->p.ackReturn(S,"A","too-much",no,new Inbound("INV_TEST","OWNER","IN",2,"I","G",q("2"),no))).isInstanceOf(BizException.class);
            assertThatThrownBy(()->p.ackReturn(S,"A","wrong-original",no,new Inbound("INV_TEST","OWNER","IN",3,"I","G",q("1"),"FOREIGN"))).isInstanceOf(BizException.class);
            p.ackReturn(S,"A","last",no,new Inbound("INV_TEST","OWNER","IN",4,"I","G",q("1"),no));
            assertThat(p.get(S,no).returnAcceptedQuantity()).isEqualByComparingTo("3");assertThat(onHand(db)).isEqualByComparingTo("17");
        });
    }
    @Test void parallelReceiptsCannotAddTheSameCumulativeQuantityTwice()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);String no=p.register(S,"A","register",source(),mapping()).replenishmentNo();
            var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{
                var a=pool.submit(()->{gate.await();return p.receive(S,"A","parallel-A",no,q("20"));});
                var b=pool.submit(()->{gate.await();return p.receive(S,"A","parallel-B",no,q("20"));});gate.countDown();
                assertThat(a.get(15,TimeUnit.SECONDS).receivedQuantity()).isEqualByComparingTo("20");
                assertThat(b.get(15,TimeUnit.SECONDS).receivedQuantity()).isEqualByComparingTo("20");
            }catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            assertThat(onHand(db)).isEqualByComparingTo("20");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM fnb_stock_movement",Integer.class)).isEqualTo(1);
        });
    }
    @Test void receiptFailureRollsBackBalanceMovementAndPermanentResultTogether()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);String no=p.register(S,"A","register",source(),mapping()).replenishmentNo();
            var sync=c.getBean(FoodStockSyncPublisher.class);doThrow(new IllegalStateException("capture failed")).when(sync).capture("E","S","RAW");
            assertThatThrownBy(()->p.receive(S,"A","retry-original",no,q("5"))).isInstanceOf(IllegalStateException.class);
            assertThat(onHand(db)).isZero();assertThat(p.get(S,no).receivedQuantity()).isZero();
            assertThat(p.findCommand(S,"REPLENISH_RECEIVE","retry-original")).isNull();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM fnb_stock_movement",Integer.class)).isZero();
            reset(sync);p.receive(S,"A","retry-original",no,q("5"));assertThat(onHand(db)).isEqualByComparingTo("5");
        });
    }
    @Test void realMetadataGateAcceptsNullableReasonButRejectsTruncatedReceiptCounters()throws Exception {
        run((c,db)->{
            try {
                var method=Class.forName("ai.neargo.shop.food.config.FoodSchemaValidator")
                        .getDeclaredMethod("missingLocalStockContract",DataSource.class);method.setAccessible(true);
                var defects=(java.util.List<?>)method.invoke(null,c.getBean(DataSource.class));
                assertThat(defects.stream().map(Object::toString).filter(v->v.startsWith("fnb_stock_replenishment")).toList()).isEmpty();
                db.execute("ALTER TABLE fnb_stock_replenishment MODIFY received_quantity DECIMAL(24,2) NOT NULL DEFAULT 0");
                var drift=(java.util.List<?>)method.invoke(null,c.getBean(DataSource.class));
                assertThat(drift.stream().map(Object::toString).toList()).contains("fnb_stock_replenishment.received_quantity.decimal_24_6");
            }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
        });
    }
    @Test void enablingDisablingAndReopeningKeepSeventeenAndOnlyAdmitNewSourceBoundary()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);db.update("DELETE FROM fnb_stock_replenishment_connection");db.update("UPDATE fnb_stock_balance SET on_hand=17");
            db.update("INSERT INTO fnb_stock_sync_config(tenant_no,entity_no,store_no,target_system,status,generation,created_at,created_by,updated_at,updated_by) VALUES('MAIN','E','S','INVENTORY','DISABLING',7,NOW(),'A',NOW(),'A')");
            assertThat(p.connection(S).enabled()).isFalse();
            var on=new ConnectionChange(true,0,"start");var active=p.configure(S,"A","on",on,new Checkpoint("INV_TEST","OWNER",10));
            assertThat(onHand(db)).isEqualByComparingTo("17");var original=p.register(S,"A","register",source(),mapping());
            var off=p.configure(S,"A","off",new ConnectionChange(false,active.revision(),"stop"),null);
            assertThat(onHand(db)).isEqualByComparingTo("17");
            var unfamiliar=new Outbound("INV_TEST","OWNER","UNKNOWN",1,"I","G",q("20"),"S",25);
            assertThatThrownBy(()->p.register(S,"A","off-new",unfamiliar,mapping())).isInstanceOf(BizException.class);
            p.receive(S,"A","wind-down",original.replenishmentNo(),q("2"));assertThat(onHand(db)).isEqualByComparingTo("19");
            var resumed=p.configure(S,"A","on-again",new ConnectionChange(true,off.revision(),"resume"),new Checkpoint("INV_TEST","OWNER",30));
            assertThat(resumed.generation()).isEqualTo(2);assertThat(onHand(db)).isEqualByComparingTo("19");
            assertThat(p.register(S,"A","old-known",source(),mapping()).replenishmentNo()).isEqualTo(original.replenishmentNo());
            assertThatThrownBy(()->p.register(S,"A","old-unknown",unfamiliar,mapping())).isInstanceOf(BizException.class);
            p.register(S,"A","fresh",new Outbound("INV_TEST","OWNER","NEW",1,"I","G",q("1"),"S",31),mapping());
            assertThat(db.queryForObject("SELECT status FROM fnb_stock_sync_config",String.class)).isEqualTo("DISABLING");
            assertThat(p.recoverConfiguration(S,"A","on",on)).isEqualTo(active);
            assertThatThrownBy(()->p.recoverConfiguration(S,"A","on",new ConnectionChange(false,0,"start"))).isInstanceOf(BizException.class);
        });
    }
    @Test void explicitOffFencesLateEnableAndSourceIdentityCannotChangeThroughReconnect()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);db.update("DELETE FROM fnb_stock_replenishment_connection");
            var off=p.configure(S,"A","off-first",new ConnectionChange(false,0,"keep local"),null);
            assertThat(off.revision()).isGreaterThan(0);
            assertThatThrownBy(()->p.configure(S,"A","late-enable",new ConnectionChange(true,0,"late"),new Checkpoint("INV_TEST","OWNER",0))).isInstanceOf(BizException.class);
            var on=p.configure(S,"A","on",new ConnectionChange(true,off.revision(),"on"),new Checkpoint("INV_TEST","OWNER",0));
            var stopped=p.configure(S,"A","off",new ConnectionChange(false,on.revision(),"off"),null);
            assertThatThrownBy(()->p.configure(S,"A","change-source",new ConnectionChange(true,stopped.revision(),"switch"),new Checkpoint("OTHER","OTHER",0))).isInstanceOf(BizException.class);
            assertThat(p.connection(S).enabled()).isFalse();assertThat(onHand(db)).isZero();
        });
    }
    private static String manual(ApplicationContext c,JdbcTemplate db,String amount,String key){
        long version=db.queryForObject("SELECT version FROM fnb_stock_balance",Long.class);
        var adjusted=c.getBean(FoodStockManagementServiceImpl.class).adjust("E","S","RAW","A",new Adjust(q(amount),version,"RECEIPT","manual arrival"),key);
        return db.queryForObject("SELECT movement_no FROM fnb_stock_movement WHERE command_no=?",String.class,adjusted.commandNo());
    }
    @Test void manualArrivalLinksRealExistingMovementWithoutAddingAgainAndCannotBeReused()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);var r=p.register(S,"A","register",source(),mapping());
            p.configure(S,"A","off",new ConnectionChange(false,p.connection(S).revision(),"off"),null);
            String movement=manual(c,db,"5","manual");assertThat(onHand(db)).isEqualByComparingTo("5");
            p.adoptManual(S,"A","link",r.replenishmentNo(),movement);p.adoptManual(S,"A","link-again",r.replenishmentNo(),movement);
            assertThat(p.get(S,r.replenishmentNo()).receivedQuantity()).isEqualByComparingTo("5");assertThat(onHand(db)).isEqualByComparingTo("5");
            assertThat(p.reconcile(S,r.replenishmentNo(),source()).issues()).containsExactly("RECEIPT_PENDING");
            assertThatThrownBy(()->p.registerManual(S,"A","reuse",new Outbound("INV_TEST","OWNER","OTHER",1,"I","G",q("20"),"S",1),mapping(),movement)).isInstanceOf(BizException.class);
            assertThatThrownBy(()->p.adoptManual(S,"A","missing",r.replenishmentNo(),"MISSING")).isInstanceOf(BizException.class);
        });
    }
    @Test void oldUnfamiliarManualOnlyOriginalCanLinkMultipleReceiptsButNeverAutoAdd()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);p.configure(S,"A","off",new ConnectionChange(false,p.connection(S).revision(),"off"),null);
            String first=manual(c,db,"3","arrival-1");var r=p.registerManual(S,"A","link",source(),mapping(),first);
            assertThat(r.status()).isEqualTo("CLOSED");assertThat(r.differenceQuantity()).isEqualByComparingTo("17");
            db.update("UPDATE fnb_stock_command SET request_hash=SHA2(JSON_REMOVE(CAST(request_snapshot AS JSON),'$[2][0].postingSequence'),256),request_snapshot=JSON_REMOVE(CAST(request_snapshot AS JSON),'$[2][0].postingSequence') WHERE operation_key='link'");
            assertThat(p.recoverManualRegistration(S,"A","link","OUT",1,mapping(),first)).isEqualTo(r);
            assertThatThrownBy(()->p.recoverManualRegistration(S,"OTHER","link","OUT",1,mapping(),first)).isInstanceOf(BizException.class);
            assertThatThrownBy(()->p.recoverManualRegistration(S,"A","link","OUT",1,mapping(),"DIFFERENT")).isInstanceOf(BizException.class);
            p.cancelCommand(S,"A","REPLENISH_MANUAL_REGISTER","cancel-before","stop");
            assertThatThrownBy(()->p.recoverManualRegistration(S,"A","cancel-before","OUT",1,mapping(),first)).isInstanceOf(BizException.class);
            String second=manual(c,db,"2","arrival-2");r=p.registerManual(S,"A","link-second",source(),mapping(),second);
            assertThat(r.receivedQuantity()).isEqualByComparingTo("5");assertThat(r.differenceQuantity()).isEqualByComparingTo("15");assertThat(onHand(db)).isEqualByComparingTo("5");
            String no=r.replenishmentNo();assertThatThrownBy(()->p.receive(S,"A","not-admission",no,q("20"))).isInstanceOf(BizException.class);
            assertThat(p.reconcile(S,no,source()).issues()).containsExactly("RECEIPT_DIFFERENCE");
        });
    }
    @Test void originalRecoveryAndSeekListRemainScopedAndReconciliationDoesNotRepairDrift()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);var first=p.register(S,"A","register",source(),mapping());
            var secondSource=new Outbound("INV_TEST","OWNER","OUT-2",1,"I","G",q("1"),"S",21);p.register(S,"A","register-2",secondSource,mapping());
            p.configure(S,"A","off",new ConnectionChange(false,p.connection(S).revision(),"off"),null);
            // Model a committed I04-B wire: no postingSequence field, and retain that original wire's own hash.
            db.update("UPDATE fnb_stock_command SET request_hash=SHA2(JSON_REMOVE(CAST(request_snapshot AS JSON),'$[2][0].postingSequence'),256),request_snapshot=JSON_REMOVE(CAST(request_snapshot AS JSON),'$[2][0].postingSequence') WHERE operation_key='register'");
            assertThat(p.recoverRegistration(S,"A","register","OUT",1,mapping())).isEqualTo(first);
            assertThatThrownBy(()->p.recoverRegistration(S,"OTHER","register","OUT",1,mapping())).isInstanceOf(BizException.class);
            var page=p.list(S,0,null,1,true);assertThat(page.items()).hasSize(1);assertThat(page.nextCursor()).isNotNull();
            assertThat(p.list(S,page.nextCursor(),page.throughId(),1,true).items()).hasSize(1);
            assertThat(p.list(new Scope("FOREIGN","S"),0,null,20,true).items()).isEmpty();
            p.receive(S,"A","receive",first.replenishmentNo(),q("5"));
            db.update("UPDATE fnb_stock_replenishment SET received_quantity=6 WHERE replenishment_no=?",first.replenishmentNo());
            assertThat(p.reconcile(S,first.replenishmentNo(),source()).issues()).contains("RECEIPT_LEDGER_MISMATCH");
            assertThat(onHand(db)).isEqualByComparingTo("5");assertThat(p.get(S,first.replenishmentNo()).receivedQuantity()).isEqualByComparingTo("6");
        });
    }
    @Test void concurrentCloseAndNewAdmissionSerializeAtTheConnectionBoundary()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{
                var registration=pool.submit(()->{gate.await();try{p.register(S,"A","race-register",source(),mapping());return true;}catch(BizException rejected){return false;}});
                var closing=pool.submit(()->{gate.await();return p.configure(S,"A","race-off",new ConnectionChange(false,0,"off"),null);});gate.countDown();
                boolean accepted=registration.get(15,TimeUnit.SECONDS);assertThat(closing.get(15,TimeUnit.SECONDS).enabled()).isFalse();
                int before=db.queryForObject("SELECT COUNT(*) FROM fnb_stock_replenishment",Integer.class);assertThat(before).isEqualTo(accepted?1:0);
                assertThatThrownBy(()->p.register(S,"A","after-off",new Outbound("INV_TEST","OWNER","AFTER",1,"I","G",q("1"),"S",99),mapping())).isInstanceOf(BizException.class);
                assertThat(onHand(db)).isZero();
            }catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
        });
    }
    @Test void concurrentManualLinksCannotCreditOneMovementToTwoOriginals()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);var a=p.register(S,"A","A",source(),mapping());
            var b=p.register(S,"A","B",new Outbound("INV_TEST","OWNER","OTHER",1,"I","G",q("20"),"S",21),mapping());String proof=manual(c,db,"5","arrival");
            var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{
                java.util.function.Function<String,Boolean> link=no->{try{gate.await();p.adoptManual(S,"A",no,no,proof);return true;}catch(BizException rejected){return false;}catch(InterruptedException e){throw new RuntimeException(e);}};
                var one=pool.submit(()->link.apply(a.replenishmentNo()));var two=pool.submit(()->link.apply(b.replenishmentNo()));gate.countDown();
                assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
                assertThat(db.queryForObject("SELECT SUM(received_quantity) FROM fnb_stock_replenishment",BigDecimal.class)).isEqualByComparingTo("5");assertThat(onHand(db)).isEqualByComparingTo("5");
            }catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
        });
    }
    @Test void permanentCancellationFencesLateWritesButNeverUndoesCommittedStock()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);String no=p.register(S,"A","register",source(),mapping()).replenishmentNo();
            assertThat(p.cancelCommand(S,"A","REPLENISH_RECEIVE","lost","stop").status()).isEqualTo("CANCELLED");
            assertThatThrownBy(()->p.receive(S,"A","lost",no,q("5"))).isInstanceOf(BizException.class);assertThat(onHand(db)).isZero();
            var actual=p.receive(S,"A","new",no,q("3"));var completed=p.cancelCommand(S,"A","REPLENISH_RECEIVE","new","stop");
            assertThat(completed.status()).isEqualTo("COMPLETED");assertThat(completed.original()).isEqualTo(actual);assertThat(onHand(db)).isEqualByComparingTo("3");
            assertThatThrownBy(()->p.cancelCommand(S,"OTHER","REPLENISH_RECEIVE","new","stop")).isInstanceOf(BizException.class);
            p.cancelCommand(S,"A","REPLENISH_REGISTER","late-register","stop");
            assertThatThrownBy(()->p.recoverRegistration(S,"A","late-register","OUT",1,mapping())).isInstanceOf(BizException.class);
        });
    }
    @Test void cancelRacingWithActualReceiptReportsOnlyTheWinningPermanentState()throws Exception {
        run((c,db)->{
            var p=c.getBean(FoodStockReplenishmentPort.class);String no=p.register(S,"A","register",source(),mapping()).replenishmentNo();
            var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{
                var receive=pool.submit(()->{gate.await();try{p.receive(S,"A","race",no,q("5"));return true;}catch(BizException rejected){return false;}});
                var cancel=pool.submit(()->{gate.await();return p.cancelCommand(S,"A","REPLENISH_RECEIVE","race","stop");});gate.countDown();
                boolean applied=receive.get(15,TimeUnit.SECONDS);var state=cancel.get(15,TimeUnit.SECONDS);
                assertThat(state.status()).isEqualTo(applied?"COMPLETED":"CANCELLED");assertThat(onHand(db)).isEqualByComparingTo(applied?"5":"0");
                assertThat(p.commandState(S,"REPLENISH_RECEIVE","race").status()).isEqualTo(state.status());
            }catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
        });
    }
    @Configuration(proxyBeanMethods=false) @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionTemplate session){var b=new MapperFactoryBean<T>(type);b.setSqlSessionTemplate(session);return b;}
        @Bean MapperFactoryBean<StockItemMapper> items(SqlSessionTemplate s){return mapper(StockItemMapper.class,s);}
        @Bean MapperFactoryBean<StockBalanceMapper> balances(SqlSessionTemplate s){return mapper(StockBalanceMapper.class,s);}
        @Bean MapperFactoryBean<StockCommandMapper> commands(SqlSessionTemplate s){return mapper(StockCommandMapper.class,s);}
        @Bean MapperFactoryBean<StockMovementMapper> movements(SqlSessionTemplate s){return mapper(StockMovementMapper.class,s);}
        @Bean MapperFactoryBean<StockReservationMapper> reservations(SqlSessionTemplate s){return mapper(StockReservationMapper.class,s);}
        @Bean MapperFactoryBean<StockReservationItemMapper> leaves(SqlSessionTemplate s){return mapper(StockReservationItemMapper.class,s);}
        @Bean MapperFactoryBean<StockReplenishmentMapper> replenishments(SqlSessionTemplate s){return mapper(StockReplenishmentMapper.class,s);}
        @Bean MapperFactoryBean<StockReplenishmentReturnMapper> returns(SqlSessionTemplate s){return mapper(StockReplenishmentReturnMapper.class,s);}
        @Bean MapperFactoryBean<StockReplenishmentConnectionMapper> connections(SqlSessionTemplate s){return mapper(StockReplenishmentConnectionMapper.class,s);}
        @Bean MapperFactoryBean<StockReplenishmentManualMapper> manual(SqlSessionTemplate s){return mapper(StockReplenishmentManualMapper.class,s);}
        @Bean FoodStockManagementServiceImpl management(StockItemMapper i,StockBalanceMapper b,StockCommandMapper cmd,StockMovementMapper m,StockReservationItemMapper l,PlatformTransactionManager tx,FoodStockSyncPublisher sync){
            return new FoodStockManagementServiceImpl(i,b,cmd,m,l,mock(GoodsCatalogQueryPort.class),JsonMapper.builder().build(),tx,sync);
        }
        @Bean FoodStockSyncPublisher sync(){return mock(FoodStockSyncPublisher.class);}
        @Bean FoodStockReplenishmentPort port(StockItemMapper i,StockBalanceMapper b,StockCommandMapper cmd,StockMovementMapper m,
                StockReplenishmentMapper r,StockReplenishmentReturnMapper a,StockReplenishmentConnectionMapper con,StockReplenishmentManualMapper man,PlatformTransactionManager tx,FoodStockSyncPublisher sync){
            return new FoodStockReplenishmentPortImpl(i,b,cmd,m,r,a,con,man,JsonMapper.builder().build(),tx,sync);
        }
        @Bean FoodLocalStockPort local(StockItemMapper i,StockBalanceMapper b,StockReservationMapper h,StockReservationItemMapper l,
                StockCommandMapper cmd,StockMovementMapper m,PlatformTransactionManager tx,FoodStockSyncPublisher sync){
            return new FoodLocalStockPortImpl(i,b,h,l,cmd,m,JsonMapper.builder().build(),tx,sync);
        }
    }
}
