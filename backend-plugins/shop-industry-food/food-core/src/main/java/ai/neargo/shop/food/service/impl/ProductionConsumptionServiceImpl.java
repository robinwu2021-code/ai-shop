package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.common.PageData;
import java.time.temporal.ChronoUnit;
import ai.neargo.shop.food.dto.ProductionContracts.LineVO;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.entity.FnbProductionConsumption;
import ai.neargo.shop.food.entity.FnbProductionConsumptionLine;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionLineMapper;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.policy.ProductionDemandPolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationDocuments;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Command;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Receipt;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Original service event is the operation identity; inventory remains the only posting and cost authority.
 * Current finished-SKU order plans authorize theoretical projections only, never a second raw-material issue.
 */
public class ProductionConsumptionServiceImpl implements ProductionConsumptionService {
    private final ProductionConsumptionMapper heads;
    private final ProductionConsumptionLineMapper lines;
    private final OrderBatchMapper batches;
    private final OrderLineMapper orderLines;
    private final KitchenTicketMapper tickets;
    private final KitchenTaskMapper tasks;
    private final ServiceEventMapper events;
    private final ConfigRevisionMapper revisions;
    private final StoreCardQueryPort stores;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    public ProductionConsumptionServiceImpl(ProductionConsumptionMapper heads,ProductionConsumptionLineMapper lines,
            OrderBatchMapper batches,OrderLineMapper orderLines,KitchenTicketMapper tickets,KitchenTaskMapper tasks,
            ServiceEventMapper events,ConfigRevisionMapper revisions,StoreCardQueryPort stores,
            InventoryProductionPort inventory,ObjectMapper json,PlatformTransactionManager manager) {
        this.heads=heads; this.lines=lines; this.batches=batches; this.orderLines=orderLines;
        this.tickets=tickets; this.tasks=tasks; this.events=events; this.revisions=revisions;
        this.stores=stores; this.json=json;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read=new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public ConsumptionVO consume(String entity,String store,String actor,String eventNo) {
        outside(); scope(entity,store,actor); key(eventNo);
        var row=transaction(() -> prepare(entity,store,actor,eventNo));
        if("CONFIRMED".equals(row.getStatus())) return transaction(() -> view(row));
        // Current order lifecycle 2/3 already reserves/issues the finished SKU. A recipe reference alone
        // does not prove that those finished-SKU leaves were excluded from the original inventory plan.
        // Preserve theoretical demand without querying or issuing raw stock under an unproved strategy.
        return transaction(() -> view(markBlocked(entity,store,eventNo,actor)));
    }

    @Override public PageData<ConsumptionVO> list(String entity,String store,String actor,ListQuery query) {
        outside(); scope(entity,store,actor);
        if(query==null || query.from()==null || query.to()==null || !query.from().isBefore(query.to())
                || query.from().getYear()<1000 || query.to().getYear()>9999
                || ChronoUnit.DAYS.between(query.from(),query.to())>93) throw BizException.of(ErrorCode.BAD_REQUEST);
        int page=query.page()==null?1:query.page(); int size=query.size()==null?20:query.size();
        if(page<1 || size<1 || size>100 || invalidFilter(query.skuNo(),32) || invalidFilter(query.itemRef(),64)
                || query.status()!=null && !Set.of("PREPARED","SUBMITTED","UNKNOWN","CONFIRMED","FAILED",
                        "BLOCKED","REVERSED").contains(query.status())) throw BizException.of(ErrorCode.BAD_REQUEST);
        return DataScopeContext.executeWithoutScope(() -> read.execute(status -> {
            long total=heads.countFiltered(entity,store,query);
            var records=total==0?List.<ConsumptionVO>of():heads.pageFiltered(entity,store,query,
                    ((long)page-1)*size,size).stream().map(this::view).toList();
            return PageData.of(records,total,page,size);
        }));
    }
    private boolean invalidFilter(String value,int max) {
        return value!=null && (value.isBlank() || value.length()>max || !value.equals(value.trim()));
    }

    @Override public ConsumptionVO get(String entity,String store,String actor,String eventNo) {
        outside(); scope(entity,store,actor); key(eventNo);
        return transaction(() -> view(find(entity,store,eventNo,true)));
    }

    private FnbProductionConsumption prepare(String entity,String store,String actor,String eventNo) {
        var prior=find(entity,store,eventNo,false);
        if(prior!=null) return prior;
        var event=events.selectOne(Wrappers.<FnbServiceEvent>lambdaQuery()
                .eq(FnbServiceEvent::getEntityNo,entity)
                .eq(FnbServiceEvent::getEventNo,eventNo));
        if(event==null) throw missing();
        var task=tasks.selectOne(Wrappers.<FnbKitchenTask>lambdaQuery()
                .eq(FnbKitchenTask::getEntityNo,entity)
                .eq(FnbKitchenTask::getTaskNo,event.getTaskNo()));
        if(task==null) throw missing();
        var ticket=tickets.selectOne(Wrappers.<FnbKitchenTicket>lambdaQuery()
                .eq(FnbKitchenTicket::getEntityNo,entity)
                .eq(FnbKitchenTicket::getTicketNo,task.getTicketNo()));
        if(ticket==null) throw missing();
        // Original batch is the stable lock shared with kitchen task commands; no empty-set lock is relied upon.
        var batch=batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getEntityNo,entity)
                .eq(FnbOrderBatch::getStoreNo,store).eq(FnbOrderBatch::getBatchNo,ticket.getBatchNo()).last("FOR UPDATE"));
        if(batch==null) throw missing();
        prior=find(entity,store,eventNo,false);
        if(prior!=null) return prior;
        if(!"START".equals(event.getAction()) || !"MAKE".equals(task.getTaskRole())
                || !"ACCEPTED".equals(batch.getStatus()) || event.getCreatedAt()==null || event.getQty()==null
                || event.getQty().signum()<=0 || event.getQty().compareTo(task.getQty())>0) throw unsupported();
        var line=orderLines.selectOne(Wrappers.<FnbOrderLine>lambdaQuery().eq(FnbOrderLine::getEntityNo,entity)
                .eq(FnbOrderLine::getFoodLineNo,task.getFoodLineNo()).eq(FnbOrderLine::getBatchNo,batch.getBatchNo()));
        if(line==null) throw missing();
        var frozen=json.readTree(batch.getFrozenRequest());
        var facts=json.treeToValue(frozen.get("facts"),DiningPriceFacts.class);
        if(facts==null || facts.payload()==null || facts.payload().cart()==null) throw conflict();
        var cart=facts.payload().cart();
        DiningPriceFactsPolicy.validate(facts,entity,store,cart.cartNo(),cart.version(),facts.payload().subjectNo());
        var choices=cart.lines().stream().filter(c -> line.getSourceLineRef().equals(c.sourceLineRef())).toList();
        if(choices.size()!=1) throw conflict();
        var choice=choices.getFirst();
        if(!line.getSkuNo().equals(choice.skuNo()) || !line.getGoodsNo().equals(choice.goodsNo())
                || choice.qty().compareTo(line.getQty())!=0) throw conflict();
        var selectedItems=facts.payload().composition().items().stream().filter(i ->
                choice.menuNo().equals(i.menuNo()) && choice.menuItemNo().equals(i.menuItemNo())
                && choice.goodsNo().equals(i.goodsNo())).toList();
        if(selectedItems.size()!=1 || selectedItems.getFirst().dish()==null
                || selectedItems.getFirst().dish().measurement()==null || !Objects.equals(line.getUnit(),
                selectedItems.getFirst().dish().measurement().unit())) throw conflict();
        var routes=json.treeToValue(frozen.get("kitchenRoutes"),FrozenRoutes.class);
        if(routes==null || routes.routes().stream().noneMatch(r -> task.getStationNo().equals(r.stationNo())
                && "MAKE".equals(r.taskRole()) && task.getRouteVersion()==r.routeVersion())) throw conflict();
        String blocked="FINISHED_SKU_STOCK_PLAN";
        Recipe recipe=null; List<InventoryProductionPort.Line> demand=List.of();
        if(task.getComponentNo()!=null) blocked="COMPONENT_DEMAND_UNSUPPORTED";
        else if(!"EA".equals(line.getUnit())) blocked="SALE_UNIT_UNSUPPORTED";
        else if(facts.payload().units().stream().anyMatch(u -> choice.sourceLineRef().equals(u.sourceLineNo())
                && !"MAIN".equals(u.kind()))) blocked="COMPOSITE_DEMAND_UNSUPPORTED";
        else {
            var makers=routes.routes().stream().filter(r -> choice.skuNo().equals(r.skuNo())
                    && "MAKE".equals(r.taskRole())).toList();
            if(makers.isEmpty() || makers.stream().noneMatch(r -> r.stationNo().equals(task.getStationNo())
                    && r.routeVersion()==task.getRouteVersion())) throw conflict();
            if(makers.size()!=1) blocked="MULTIPLE_MAKE_ROUTES";
            else {
                recipe=recipe(entity,store,facts,choice);
                if(recipe==null) blocked="FROZEN_RECIPE_MISSING";
                else {
                    try { demand=ProductionDemandPolicy.evaluate(recipe.body(),choice,event.getQty()); }
                    catch(BizException unavailable) {
                        if(unavailable.errorCode()==ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)
                            blocked="FROZEN_RECIPE_DEMAND_UNSUPPORTED";
                        else if(unavailable.errorCode()==ErrorCode.CONFLICT)
                            blocked="FROZEN_RECIPE_ADJUSTMENT_CONFLICT";
                        else throw unavailable;
                    }
                }
            }
        }
        String purpose="REMAKE".equals(ticket.getKind())?"REMAKE":"PRODUCTION";
        var command=demand.isEmpty()?null:new Command(entity,store,eventNo,purpose,
                event.getCreatedAt().toInstant(ZoneOffset.UTC),demand);
        // Schema two is a local blocked projection, never an inventory posting command or receipt.
        // The original event/source identity and explicit reason remain queryable even without theory lines.
        var snapshot=new BlockedProjection(2,blocked,entity,store,eventNo,task.getTaskNo(),ticket.getTicketNo(),
                line.getFoodLineNo(),task.getComponentNo(),facts.sourceHash(),command);
        var row=new FnbProductionConsumption(); row.setEntityNo(entity); row.setStoreNo(store);
        row.setConsumptionNo(BizKey.next("FCON")); row.setSourceEventNo(eventNo); row.setPurpose(purpose);
        row.setRequestSnapshot(json.writeValueAsString(snapshot));
        row.setRequestHash(command==null?PricingSnapshotHash.of(snapshot):fingerprint(command));
        row.setStatus("BLOCKED"); row.setBusinessDate(batch.getBusinessDate()); row.setOccurredAt(event.getCreatedAt());
        row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setVersion(0L); heads.insert(row);
        int number=0;
        for(var material:demand) {
            var child=new FnbProductionConsumptionLine(); child.setEntityNo(entity);
            child.setConsumptionNo(row.getConsumptionNo()); child.setLineNo(++number);
            child.setComponentPath(task.getTaskNo()+"/"+choice.sourceLineRef()+"/"+material.itemRef());
            child.setItemRef(material.itemRef()); child.setTheoryQty(material.quantity()); child.setUnit(material.unit());
            child.setRecipeRevisionNo(recipe.revisionNo()); child.setStatus("PENDING");
            child.setCreatedBy(actor); child.setUpdatedBy(actor); child.setVersion(0L); lines.insert(child);
        }
        return row;
    }

    private record BlockedProjection(int schema,String blockReason,String entityNo,String storeNo,
            String sourceEventNo,String taskNo,String ticketNo,String foodLineNo,String componentNo,
            String frozenSourceHash,Command theory) { }
    private record Recipe(String revisionNo,RecipePublicationSnapshot body) { }
    private Recipe recipe(String entity,String store,DiningPriceFacts facts,OrderLineInput choice) {
        var menus=facts.payload().menus().stream().filter(m -> choice.menuNo().equals(m.menuNo())
                && choice.menuRevisionNo().equals(m.revisionNo())).toList();
        if(menus.size()!=1 || facts.payload().composition()==null) throw conflict();
        var catalog=facts.payload().composition().items().stream().filter(i -> choice.menuNo().equals(i.menuNo())
                && choice.menuItemNo().equals(i.menuItemNo()) && choice.goodsNo().equals(i.goodsNo())).toList();
        if(catalog.size()!=1) throw conflict();
        var skus=catalog.getFirst().skus().stream().filter(s -> choice.skuNo().equals(s.skuNo())).toList();
        if(skus.size()!=1) throw conflict();
        String explicit=skus.getFirst().recipeNo();
        var selected=new ArrayList<Recipe>();
        for(var dep:menus.getFirst().dependencies()) if("RECIPE".equals(dep.kind())) {
            var revision=revisions.findRevision(entity,dep.revisionNo());
            if(revision==null || !"RECIPE".equals(revision.getConfigKind()) || revision.getContentSchema()!=3
                    || !dep.configNo().equals(revision.getConfigNo()) || dep.revision()!=revision.getRevision()
                    || !dep.contentHash().equals(revision.getContentHash())
                    || !PublicationDocuments.hash(json,revision.getDefinitionSnapshot()).equals(dep.contentHash())) throw conflict();
            var body=RecipePublicationDocuments.validate(json,dep.configNo(),revision.getDefinitionSnapshot());
            RecipePublicationDocuments.scope(body,entity,store);
            if(choice.skuNo().equals(body.recipe().skuNo())
                    && (explicit==null?"DEFAULT".equals(body.recipe().recipeCode()):explicit.equals(body.recipe().recipeNo())))
                selected.add(new Recipe(dep.revisionNo(),body));
        }
        if(selected.isEmpty() && explicit==null) return null;
        // An explicit frozen recipe reference must resolve; a broken promise is not a normal missing recipe.
        if(selected.size()!=1) throw conflict();
        return selected.getFirst();
    }

    /** Same canonical envelope as the original inventory owner, with sorted normalized decimal lines. */
    public static String fingerprint(Command command) { return InventoryProductionFacts.hash(command); }
    public static void validate(Command command,Receipt receipt) {
        if(receipt==null || !command.eventNo().equals(receipt.eventNo())
                || !fingerprint(command).equals(receipt.requestHash())
                || !Set.of("ABSENT","UNKNOWN","CONFIRMED","REVERSED").contains(receipt.status())
                || (receipt.costMinor()==null)!=(receipt.currency()==null)
                || receipt.currency()!=null && !receipt.currency().matches("[A-Z]{3}")
                || receipt.costMinor()!=null && receipt.costMinor()<0) throw conflict();
        if("ABSENT".equals(receipt.status())) {
            if(receipt.documentNo()!=null || !receipt.lines().isEmpty() || receipt.costMinor()!=null) throw conflict();
            return;
        }
        if("CONFIRMED".equals(receipt.status()) || !receipt.lines().isEmpty()) {
            if(receipt.documentNo()==null || receipt.documentNo().isBlank()
                    || receipt.lines().size()!=command.lines().size()) throw conflict();
            var actual=receipt.lines().stream()
                    .sorted(Comparator.comparing(InventoryProductionPort.Line::itemRef)).toList();
            var expected=command.lines().stream()
                    .sorted(Comparator.comparing(InventoryProductionPort.Line::itemRef)).toList();
            for(int i=0;i<actual.size();i++) {
                var want=expected.get(i); var got=actual.get(i);
                if(!Objects.equals(want.itemRef(),got.itemRef()) || !Objects.equals(want.unit(),got.unit())
                        || !Objects.equals(want.definitionHash(),got.definitionHash()) || got.quantity()==null
                        || want.quantity().compareTo(got.quantity())!=0) throw conflict();
            }
        }
    }

    private FnbProductionConsumption markBlocked(String entity,String store,String event,String actor) {
        var row=find(entity,store,event,true);
        if(!Set.of("CONFIRMED","BLOCKED").contains(row.getStatus())) {
            row.setStatus("BLOCKED"); row.setUpdatedBy(actor); if(heads.updateById(row)!=1) throw conflict();
        }
        return row;
    }
    private FnbProductionConsumption find(String entity,String store,String event,boolean required) {
        var rows=heads.selectList(Wrappers.<FnbProductionConsumption>lambdaQuery()
                .eq(FnbProductionConsumption::getEntityNo,entity).eq(FnbProductionConsumption::getStoreNo,store)
                .eq(FnbProductionConsumption::getSourceEventNo,event).last(required?"FOR UPDATE":""));
        if(rows.size()>1) throw conflict();
        if(rows.isEmpty()) { if(required) throw missing(); return null; }
        return rows.getFirst();
    }
    private List<FnbProductionConsumptionLine> children(String entity,String consumption) {
        return lines.selectList(Wrappers.<FnbProductionConsumptionLine>lambdaQuery()
                .eq(FnbProductionConsumptionLine::getTenantNo,"MAIN")
                .eq(FnbProductionConsumptionLine::getDeleted,0)
                .eq(FnbProductionConsumptionLine::getEntityNo,entity)
                .eq(FnbProductionConsumptionLine::getConsumptionNo,consumption)
                .orderByAsc(FnbProductionConsumptionLine::getLineNo));
    }
    private ConsumptionVO view(FnbProductionConsumption row) {
        var receipt=row.getReceiptSnapshot()==null?null:json.readValue(row.getReceiptSnapshot(),Receipt.class);
        boolean confirmed="CONFIRMED".equals(row.getStatus()) && receipt!=null && "CONFIRMED".equals(receipt.status());
        var child=children(row.getEntityNo(),row.getConsumptionNo()).stream().map(l -> new LineVO(l.getLineNo(),
                l.getComponentPath(),l.getItemRef(),l.getTheoryQty(),l.getUnit(),l.getRecipeRevisionNo(),
                l.getInventoryReceiptRef(),l.getActualQty(),l.getCostMinor(),l.getCurrency(),l.getStatus())).toList();
        return new ConsumptionVO(row.getConsumptionNo(),row.getSourceEventNo(),row.getPurpose(),row.getStatus(),
                row.getRequestHash(),row.getInventoryOperationRef(),row.getBusinessDate(),row.getOccurredAt(),child,
                confirmed?"INVENTORY_ISSUE_ONLY":child.isEmpty()?"THEORY_UNAVAILABLE:"+blockedReason(row):
                "THEORETICAL_ONLY",!"CONFIRMED".equals(row.getStatus()),
                confirmed?receipt.costMinor():null,confirmed?receipt.currency():null,row.getVersion());
    }
    private String blockedReason(FnbProductionConsumption row) {
        var snapshot=json.readTree(row.getRequestSnapshot());
        String reason=snapshot.path("blockReason").asString("");
        if(!reason.matches("[A-Z0-9_]{1,64}")) throw conflict();
        return reason;
    }
    private void scope(String entity,String store,String actor) {
        key(entity); key(store); key(actor);
        var card=stores.cards(List.of(store)).get(store);
        if(card==null || !entity.equals(card.entityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private void key(String value) { if(value==null||value.isBlank()||value.length()>32) throw BizException.of(ErrorCode.BAD_REQUEST); }
    private void outside() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Production workflow cannot join a caller transaction");
    }
    private <T> T transaction(Supplier<T> work) {
        try { return DataScopeContext.executeWithoutScope(() -> tx.execute(status -> work.get())); }
        catch(ConcurrencyFailureException|DuplicateKeyException conflict) { throw conflict(); }
    }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
    private static BizException unsupported() { return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
