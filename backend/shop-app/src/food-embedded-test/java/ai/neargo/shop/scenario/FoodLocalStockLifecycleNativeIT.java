package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.FoodStockItemService;
import ai.neargo.shop.food.service.FoodStockBalanceService;
import ai.neargo.shop.food.port.FoodStockReportFactsPortAdapter;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodLocalStockPort.*;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.report.ReportContracts.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real MySQL transactions, row locks and permanent receipts; unique test scopes are never truncated. */
@TestPropertySource(properties={"shop.food.local-stock.enabled=true","shop.outbox.sources.platform.mode=UNIFIED","shop.outbox.sources.inventory.mode=UNIFIED"})
class FoodLocalStockLifecycleNativeIT extends FoodEmbeddedStartupIT {
    @Test void parallelProductionDeltasAndPartialReturnAreAtomicAndDoNotLoseAnEvent() throws Exception {
        var f=new Fixture("TRACKED","5");var plan=f.plan("4");var hold=f.reserve(plan);var accepted=f.confirm(hold);
        var increments=List.of(new Target("L",BigDecimal.ONE));var begin=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        Receipt first;
        try {
            var one=pool.submit(()->{begin.await();return f.issue(accepted,"one",increments);});
            var two=pool.submit(()->{begin.await();return f.issue(accepted,"two",increments);});begin.countDown();
            first=one.get(20,TimeUnit.SECONDS);var second=two.get(20,TimeUnit.SECONDS);
            assertThat(first.commandNo()).isNotEqualTo(second.commandNo());
        } finally {pool.shutdownNow();}
        assertThat(f.balance().onHand()).isEqualByComparingTo("3");assertThat(f.balance().reservedQty()).isEqualByComparingTo("2");
        assertThat(f.issue(accepted,"one",increments)).isEqualTo(first);
        var resold=f.returns(accepted,first.movementRefs().getFirst(),"resale","0.4","RESALE");
        assertThat(resold.movementRefs()).hasSize(1);f.returns(accepted,first.movementRefs().getFirst(),"loss","0.6","LOSS");
        assertThat(f.balance().onHand()).isEqualByComparingTo("3.4");
        assertThatThrownBy(()->f.returns(accepted,first.movementRefs().getFirst(),"overflow","0.1","RESALE")).isInstanceOf(BizException.class);
        assertThat(f.balance().onHand()).isEqualByComparingTo("3.4");
        assertThatThrownBy(()->f.scoped(()->f.stock.getReservation(new FoodLocalStockPort.Scope(f.entity,"OTHER"),hold.reservationNo())))
                .isInstanceOf(BizException.class);
    }
    @Test void concurrentIndependentReservationsCannotOversellOnePhysicalBalance() throws Exception {
        var f=new Fixture("TRACKED","10");var plan=f.plan("7");
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        java.util.function.Function<String,Boolean> attempt=source->{
            var raw=new Command(source,"","CORE_ORDER",source,0,0);
            var command=new Command(source,FoodStockFingerprint.request(f.json,"RESERVE",raw,List.of(plan)),"CORE_ORDER",source,0,0);
            try { f.scoped(()->f.stock.reserve(f.scope,command,plan));return true; }
            catch(BizException rejected){return false;}
        };
        try {
            var a=pool.submit(()->{gate.await();return attempt.apply("A");});
            var b=pool.submit(()->{gate.await();return attempt.apply("B");});gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
        assertThat(f.balance().onHand()).isEqualByComparingTo("10");
        assertThat(f.balance().reservedQty()).isEqualByComparingTo("7");
    }
    @Test void exactOwnerBridgeSupportsPrepayAndPostpayWithoutAnyNativeInventoryPosting() {
        for(int protocol:List.of(2,3)) {
            var f=new Fixture("TRACKED","5");
            var scope=new ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope("MAIN",f.entity,"ORDER"+protocol);
            var leaf=new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf("L",f.item,BigDecimal.ONE,
                    "EA",FoodStockFingerprint.sha256("definition"),null,"SALES_SKU");
            var branch=new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch("C","FULFILLMENT","S",
                    "FINISHED_SKU",BigDecimal.ONE,"EA",List.of(leaf));
            var raw=new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan(scope,"PLAN"+protocol,"",List.of(branch));
            var plan=new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan(scope,raw.planNo(),
                    ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts.hash(raw),raw.branches());
            var router=context.getBean(ai.neargo.shop.trade.stock.IndustryStockOwnerRouter.class);
            var bridge=router.exact("FOOD_LOCAL",plan,protocol);
            assertThat(bridge.reserve(plan,3600,protocol).status()).isEqualTo("HELD");
            if(protocol==3) assertThat(bridge.acceptUnpaid(scope).status()).isEqualTo("ACCEPTED_UNPAID");
            assertThat(bridge.allocatePaid(scope).status()).isEqualTo("ALLOCATED");
            var issued=bridge.issueTo(scope,"handover",plan.planHash(),"HANDOVER",
                    List.of(new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target("L",BigDecimal.ONE)));
            assertThat(issued.lines()).singleElement().satisfies(v->{
                assertThat(v.itemRef()).isEqualTo(f.item);assertThat(v.issuedQty()).isEqualByComparingTo("1");});
            assertThat(f.balance().onHand()).isEqualByComparingTo("4");
            bridge.issueTo(scope,"handover",plan.planHash(),"HANDOVER",
                    List.of(new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target("L",BigDecimal.ONE)));
            assertThat(f.balance().onHand()).isEqualByComparingTo("4");
        }
    }
    @Test void untrackedProgressAndSupplementNeverBecomePhysicalInventoryOrUsageReports() {
        var f=new Fixture("UNTRACKED","0");var plan=f.plan("100");var accepted=f.confirm(f.reserve(plan));
        var result=f.issue(accepted,"workflow",List.of(new Target("L",new BigDecimal("3"))));
        assertThat(result.items().getFirst().trackingMode()).isEqualTo("UNTRACKED");
        assertThat(result.items().getFirst().issued()).isEqualByComparingTo("3");
        assertThat(f.balance().onHand()).isNull();assertThat(f.balance().reservedQty()).isNull();
        assertThat(sql.queryForObject("SELECT action FROM fnb_stock_movement WHERE command_no=?",String.class,result.commandNo()))
                .isEqualTo("NO_STOCK_ISSUE");
        var extra=f.plan("2");var source=f.source;f.source="extra";
        var raw=new Command("extra","","CORE_SUPPLEMENT",f.source,0,0);
        var extraPlan=new Plan(extra.planHash(),null,extra.leaves(),FoodStockFingerprint.sha256("origin"),1);
        extraPlan=new Plan(FoodStockFingerprint.plan(f.json,extraPlan),null,extraPlan.leaves(),extraPlan.sourcePlanHash(),1);
        var fixed=extraPlan;var extraCommand=new Command(raw.operationKey(),FoodStockFingerprint.request(f.json,"SUPPLEMENT",raw,List.of(fixed)),raw.sourceSystem(),raw.sourceNo(),0,0);
        var supplemental=f.scoped(()->f.stock.consumeSupplement(f.scope,extraCommand,fixed));f.source=source;
        assertThat(supplemental.items().getFirst().trackingMode()).isEqualTo("UNTRACKED");
        var report=new FoodStockReportFactsPortAdapter(context.getBean(StockBalanceMapper.class),context.getBean(StockItemMapper.class),
                context.getBean(StockMovementMapper.class),context.getBean(StockCommandMapper.class),f.json,
                context.getBean("transactionManager",PlatformTransactionManager.class),ZoneId.systemDefault().getId());
        Instant now=Instant.now();var day=new DayEvidence("DAY","POLICY",0,"UTC",0,now.minusSeconds(3600),now.plusSeconds(3600));
        var snapshot=report.readSnapshot(new SourceQuery(new Partition(new ai.neargo.shop.spi.report.ReportContracts.Scope("MAIN",f.entity,"S"),
                LocalDate.now(),1),day,"U",List.of(),100));
        assertThat(snapshot.facts()).noneMatch(point->point.metricCode().equals("MATERIAL_ACTUAL_ISSUE_QTY"));
        assertThat(snapshot.facts()).noneMatch(point->point.metricCode().equals("GOODS_SALES_COST_AMOUNT"));
    }
    @Test void durableWithdrawalRejectsLateReserveAndKeepsPhysicalStockUntouched() {
        var f=new Fixture("TRACKED","5");var plan=f.plan("2");
        var command=f.command("WITHDRAW","cancel",0,List.of(plan));
        f.scoped(()->f.stock.withdraw(f.scope,command,plan));
        assertThatThrownBy(()->f.reserve(plan)).isInstanceOf(BizException.class);
        assertThat(f.balance().onHand()).isEqualByComparingTo("5");assertThat(f.balance().reservedQty()).isZero();
    }
    @Test void issuedHistoricalStockPreventsPolicySwitchUntilItsPhysicalDispositionIsComplete() {
        var f=new Fixture("TRACKED","1");var accepted=f.confirm(f.reserve(f.plan("1")));
        var issue=f.issue(accepted,"all",List.of(new Target("L",BigDecimal.ONE)));
        var balance=f.balance();
        assertThatThrownBy(()->f.scoped(()->f.balances.changePolicy(f.entity,"S",f.item,"U",
                new Policy("UNTRACKED","ACTIVE",balance.version(),"MODE","switch"),"switch-before-return")))
                .isInstanceOf(BizException.class);
        f.returns(accepted,issue.movementRefs().getFirst(),"loss-disposition","1","LOSS");
        var after=f.balance();var changed=f.scoped(()->f.balances.changePolicy(f.entity,"S",f.item,"U",
                new Policy("UNTRACKED","ACTIVE",after.version(),"MODE","switch"),"switch-after-loss"));
        assertThat(changed.trackingMode()).isEqualTo("UNTRACKED");
    }
    @Test void partialReturnRejectsAnExhaustedProductionEventBeforeClaimingTheOtherPortion() {
        var f=new Fixture("TRACKED","6");String tag=f.entity.substring(1);
        String batch="B"+tag,line="O"+tag,task="T"+tag,number="R"+tag,returnItem="RI"+tag;
        String originalHash=FoodStockFingerprint.sha256("original:"+tag);var draft=f.plan("4");
        var translated=new Plan("",null,draft.leaves(),originalHash,2);
        translated=new Plan(FoodStockFingerprint.plan(f.json,translated),null,translated.leaves(),originalHash,2);
        var accepted=f.confirm(f.reserve(translated));
        var first=f.issue(accepted,"A",List.of(new Target("L",new BigDecimal("2"))));
        var second=f.issue(accepted,"B",List.of(new Target("L",new BigDecimal("2"))));
        seed(f.entity,"fnb_order_batch",pairs("batch_no",batch,"store_no","S","request_key",batch,"request_hash",originalHash,
                "source","POS","operator_no","U","status","BOUND","frozen_request","{}","cart_no","C"+tag,
                "cart_version",0,"quote_ref","Q"+tag,"quote_hash",originalHash,"business_date",LocalDate.now(),
                "policy_no","P"+tag,"fulfillment","DINE_IN","pay_mode","POSTPAY","actor_realm","STAFF","quota_claim_refs","[]"));
        seed(f.entity,"fnb_order_line",pairs("food_line_no",line,"batch_no",batch,"goods_no","G"+tag,"sku_no","SKU"+tag,
                "qty",2,"selection_version",0,"display_snapshot","{}","source_line_ref","SOURCE","config_revision_nos","[]","unit","EA"));
        seed(f.entity,"fnb_kitchen_task",pairs("task_no",task,"ticket_no","K"+tag,"food_line_no",line,"station_no","S"+tag,
                "route_version",0,"qty",2,"making_qty",2,"ready_qty",0,"served_qty",0,"void_qty",0,"status","MAKING","task_part_key","ALL","task_role","MAKE"));
        seed(f.entity,"fnb_return",pairs("return_no",number,"store_no","S","reason","return","status","APPROVED",
                "financial_status","PENDING","kitchen_status","HANDLED","batch_no",batch));
        seed(f.entity,"fnb_return_item",pairs("return_item_no",returnItem,"return_no",number,"food_line_no",line,"qty",2,"kitchen_disposition","KEEP"));
        String eventA="EA"+tag,eventB="EB"+tag;
        for(var event:List.of(eventA,eventB)) {
            var nativeIssue=event.equals(eventA)?first:second;
            seed(f.entity,"fnb_service_event",pairs("event_no",event,"task_no",task,"action","START","qty",1,"operation_key",event));
            var command=new ai.neargo.shop.spi.inventory.InventoryProductionPort.Command(f.entity,"S",event,"PRODUCTION",Instant.now(),
                    List.of(new ai.neargo.shop.spi.inventory.InventoryProductionPort.Line(f.item,new BigDecimal("2"),"EA",FoodStockFingerprint.sha256("{}"))),
                    new ai.neargo.shop.spi.inventory.InventoryProductionPort.PlanProof(new ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope("MAIN",f.entity,"source"),
                            originalHash,"COMPONENT",List.of(new ai.neargo.shop.spi.inventory.InventoryProductionPort.SourceLeaf("L",new BigDecimal("2")))));
            String commandHash=ai.neargo.shop.spi.inventory.InventoryProductionFacts.hash(command);
            var receipt=new ai.neargo.shop.spi.inventory.InventoryProductionPort.Receipt(event,commandHash,"CONFIRMED",nativeIssue.commandNo(),command.lines(),null,null,
                    List.of(new ai.neargo.shop.spi.inventory.InventoryProductionPort.IssuedLine("L",f.item,new BigDecimal("2"),"EA",nativeIssue.commandNo(),nativeIssue.movementRefs().getFirst(),null,null,null,"UNKNOWN")));
            seed(f.entity,"fnb_production_consumption",pairs("consumption_no","C"+event,"store_no","S","source_event_no",event,
                    "purpose","PRODUCTION","request_hash",commandHash,"status","CONFIRMED","inventory_operation_ref",nativeIssue.commandNo(),
                    "business_date",LocalDate.now(),"occurred_at",LocalDateTime.now(),"request_snapshot",f.json.writeValueAsString(command),"receipt_snapshot",f.json.writeValueAsString(receipt)));
        }
        var service=context.getBean(ai.neargo.shop.food.service.FoodReturnStockService.class);
        var inputA=new ai.neargo.shop.food.service.FoodReturnStockService.Apply(returnItem,eventA,BigDecimal.ONE,"RESALE","UNUSED","not prepared","EVIDENCE-A");
        var result=f.scoped(()->service.apply(f.entity,"S",number,"U",inputA,"A"));assertThat(result.status()).isEqualTo("COMPLETED");
        assertThatThrownBy(()->f.scoped(()->service.apply(f.entity,"S",number,"U",inputA,"A-duplicate-event"))).isInstanceOf(BizException.class);
        var inputB=new ai.neargo.shop.food.service.FoodReturnStockService.Apply(returnItem,eventB,BigDecimal.ONE,"LOSS","DAMAGED","discarded","EVIDENCE-B");
        assertThat(f.scoped(()->service.apply(f.entity,"S",number,"U",inputB,"B")).status()).isEqualTo("COMPLETED");
        assertThat(f.scoped(()->service.apply(f.entity,"S",number,"U",inputA,"A"))).isEqualTo(result);
        assertThat(f.balance().onHand()).isEqualByComparingTo("4");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_return_stock_disposition WHERE entity_no=?",Integer.class,f.entity)).isEqualTo(2);
    }
    private java.util.Map<String,Object> pairs(Object... values) {
        var result=new java.util.LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;
    }
    private void seed(String entity,String table,java.util.Map<String,Object> values) {
        var row=pairs("tenant_no","MAIN","entity_no",entity,"created_at",LocalDateTime.now(),"updated_at",LocalDateTime.now(),"created_by","U","updated_by","U");
        row.putAll(values);sql.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",java.util.Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    @Test void manualStockAdjustmentsKeepSignedMovementMetricsAndNoInventedCost() {
        var f=new Fixture("TRACKED","0");
        var plus=f.scoped(()->f.balances.adjust(f.entity,"S",f.item,"U",new Adjust(new BigDecimal("3"),f.balance().version(),"COUNT","add"),"add"));
        f.scoped(()->f.balances.adjust(f.entity,"S",f.item,"U",new Adjust(new BigDecimal("-1"),plus.version(),"COUNT","subtract"),"subtract"));
        var report=new FoodStockReportFactsPortAdapter(context.getBean(StockBalanceMapper.class),context.getBean(StockItemMapper.class),
                context.getBean(StockMovementMapper.class),context.getBean(StockCommandMapper.class),f.json,
                context.getBean("transactionManager",PlatformTransactionManager.class),ZoneId.systemDefault().getId());
        var now=Instant.now();var day=new DayEvidence("DAY","POLICY",0,"UTC",0,now.minusSeconds(3600),now.plusSeconds(3600));
        var result=report.readSnapshot(new SourceQuery(new Partition(new ai.neargo.shop.spi.report.ReportContracts.Scope("MAIN",f.entity,"S"),LocalDate.now(),1),day,"U",List.of(),100));
        assertThat(result.facts().stream().filter(v->v.metricCode().equals("STOCK_ADJUSTMENT_QTY"))
                .map(v->v.value().quantityValue()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("2");
        assertThat(result.facts()).noneMatch(v->v.metricCode().equals("GOODS_SALES_COST_AMOUNT"));
    }
    @Test void optionalMirrorHandlesReorderedRealEventsAndNeverPostsNativeInventory() {
        String entity="M0001",store="ST-M0001",tag=UUID.randomUUID().toString().replace("-","").substring(0,12);
        var inventorySql=new org.springframework.jdbc.core.JdbcTemplate(context.getBean("invDataSource",javax.sql.DataSource.class));
        var before=inventorySql.queryForList("SELECT owner_id,location_id,item_id,on_hand,reserved,version FROM inv_stock_balance ORDER BY owner_id,location_id,item_id");
        var stockItems=context.getBean(FoodStockItemService.class);var stockBalances=context.getBean(FoodStockBalanceService.class);
        var sync=context.getBean(ai.neargo.shop.food.service.FoodStockSyncService.class);
        var dispatcher=context.getBean(ai.neargo.shop.food.service.impl.FoodStockSyncDispatcher.class);
        var target=context.getBean(ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.class);
        var mapper=context.getBean(ObjectMapper.class);
        var source=DataScopeContext.executeWithoutScope(()->stockItems.createItem(entity,store,"U",new CreateItem("MATERIAL",null,"mirror proof","EA","TRACKED"),"mi"+tag));
        var balance=DataScopeContext.executeWithoutScope(()->stockBalances.initialize(entity,store,"U",new Initialize(source.itemNo(),"TRACKED","ACTIVE",new BigDecimal("3"),"COUNT","opening"),"mb"+tag));
        var config=DataScopeContext.executeWithoutScope(()->sync.config(entity,store));assertThat(config.status()).isEqualTo("OFF");
        assertThat(DataScopeContext.executeWithoutScope(()->sync.preflight(entity,store)).allowed()).isTrue();
        var enabled=DataScopeContext.executeWithoutScope(()->sync.configure(entity,store,"U",new ai.neargo.shop.food.service.FoodStockSyncService.ConfigCommand(true,config.version(),"native proof"),"enable"+tag));
        var activation=sql.queryForList("SELECT sync_no FROM fnb_stock_sync_delivery WHERE entity_no=? AND store_no=? AND generation=? AND item_no='__CONTROL__' ORDER BY id DESC LIMIT 1",entity,store,enabled.generation()).getFirst();
        var envelope=stockEnvelope((String)activation.get("sync_no"));
        assertThat(DataScopeContext.executeWithoutScope(()->dispatcher.handle(envelope)).kind()).isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.SENT);
        DataScopeContext.executeWithoutScope(()->stockBalances.adjust(entity,store,source.itemNo(),"U",new Adjust(new BigDecimal("2"),balance.version(),"COUNT","increase"),"adjust"+tag));
        var changes=sql.queryForList("SELECT sync_no FROM fnb_stock_sync_delivery WHERE entity_no=? AND store_no=? AND generation=? AND item_no=? ORDER BY id DESC",entity,store,enabled.generation(),source.itemNo());
        assertThat(changes).hasSize(2);
        for(var change:changes){var original=stockEnvelope((String)change.get("sync_no"));
            assertThat(DataScopeContext.executeWithoutScope(()->dispatcher.handle(original)).kind()).isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.SENT);}
        var mirror=target.queryProjection(new ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Scope(entity,store),List.of(source.itemNo())).getFirst();
        assertThat(mirror.onHand()).isEqualByComparingTo("5");
        var repeated=stockEnvelope((String)changes.getFirst().get("sync_no"));DataScopeContext.executeWithoutScope(()->dispatcher.handle(repeated));
        assertThat(target.queryProjection(new ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Scope(entity,store),List.of(source.itemNo())).getFirst()).isEqualTo(mirror);
        var active=DataScopeContext.executeWithoutScope(()->sync.config(entity,store));
        DataScopeContext.executeWithoutScope(()->sync.configure(entity,store,"U",new ai.neargo.shop.food.service.FoodStockSyncService.ConfigCommand(false,active.version(),"native disable"),"disable"+tag));
        var closing=sql.queryForList("SELECT sync_no FROM fnb_stock_sync_delivery WHERE entity_no=? AND store_no=? AND generation=? AND item_no='__CONTROL__' ORDER BY id DESC LIMIT 1",entity,store,enabled.generation()).getFirst();
        var close=stockEnvelope((String)closing.get("sync_no"));DataScopeContext.executeWithoutScope(()->dispatcher.handle(close));
        assertThat(DataScopeContext.executeWithoutScope(()->sync.config(entity,store)).status()).isEqualTo("OFF");
        assertThat(inventorySql.queryForList("SELECT owner_id,location_id,item_id,on_hand,reserved,version FROM inv_stock_balance ORDER BY owner_id,location_id,item_id")).isEqualTo(before);
    }
    private ai.neargo.shop.event.outbox.OutboxEnvelope stockEnvelope(String sync) {
        return sql.queryForObject("SELECT * FROM sys_outbox WHERE aggregate_type='FOOD_STOCK_SYNC' AND aggregate_id=? ORDER BY id LIMIT 1",(rs,n)->
                new ai.neargo.shop.event.outbox.OutboxEnvelope(rs.getString("event_no"),rs.getString("source_system"),rs.getString("source_event_no"),
                        rs.getString("aggregate_type"),rs.getString("aggregate_id"),rs.getString("event_type"),rs.getInt("schema_version"),
                        rs.getString("stream_key"),rs.getLong("stream_sequence"),rs.getString("payload"),rs.getTimestamp("occurred_at").toLocalDateTime().toInstant(ZoneOffset.UTC)),sync);
    }
    private class Fixture {
        final String entity="N"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        final FoodLocalStockPort.Scope scope=new FoodLocalStockPort.Scope(entity,"S");
        final FoodLocalStockPort stock=context.getBean(FoodLocalStockPort.class);
        final FoodStockBalanceService balances=context.getBean(FoodStockBalanceService.class);
        final ObjectMapper json=context.getBean(ObjectMapper.class);final String item;String source="source";
        Fixture(String mode,String amount) {
            var created=scoped(()->context.getBean(FoodStockItemService.class).createItem(entity,"S","U",new CreateItem("MATERIAL",null,"Native material","EA",mode),"create"));
            item=created.itemNo();scoped(()->balances.initialize(entity,"S","U",new Initialize(item,mode,"ACTIVE",new BigDecimal(amount),"COUNT","opening"),"initialize"));
        }
        Plan plan(String amount){var leaf=new Leaf("L","COMPONENT",item,"EA",new BigDecimal(amount),"PRODUCTION","{}",FoodStockFingerprint.sha256("{}"));
            var plan=new Plan("",null,List.of(leaf));return new Plan(FoodStockFingerprint.plan(json,plan),null,plan.leaves());}
        Command command(String action,String key,long version,Object payload){var raw=new Command(key,"","CORE_ORDER",source,0,version);
            return new Command(key,FoodStockFingerprint.request(json,action,raw,payload),raw.sourceSystem(),raw.sourceNo(),0,version);}
        Receipt reserve(Plan plan){return scoped(()->stock.reserve(scope,command("RESERVE","reserve",0,List.of(plan)),plan));}
        Receipt confirm(Receipt hold){return scoped(()->stock.confirm(scope,command("CONFIRM","confirm",hold.lifecycleVersion(),List.of(hold.reservationNo())),hold.reservationNo()));}
        Receipt issue(Receipt accepted,String key,List<Target> increments){return scoped(()->stock.issueDelta(scope,
                command("ISSUE_DELTA",key,accepted.lifecycleVersion(),List.of(accepted.reservationNo(),increments)),accepted.reservationNo(),increments));}
        Receipt returns(Receipt accepted,String original,String key,String amount,String disposition){
            var targets=List.of(new ReturnTarget("L",original,new BigDecimal(amount),key,disposition,"COUNT","reason","U",false,true));
            return scoped(()->stock.returnIssued(scope,command("RETURN",key,accepted.lifecycleVersion(),List.of(accepted.reservationNo(),targets)),accepted.reservationNo(),targets));}
        BalanceVO balance(){return scoped(()->balances.getBalance(entity,"S",item));}
        <T>T scoped(Supplier<T> call){return DataScopeContext.executeWithoutScope(call::get);}
    }
}
