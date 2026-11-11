package ai.neargo.shop.promotion.cost;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.FactView;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.GenerateStatement;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.RevisionCommand;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.RuleView;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.SaveRule;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.StatementView;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Reconciles immutable discounts with paid orders and successful refunds, without a high-water mark or payment API. */
@Service
public class BrandPromotionCostServiceImpl implements BrandPromotionCostService {
    private final JdbcTemplate db;
    private final BrandPromotionScopePort organization;
    private final ActivityCommandDeduplicator commands;
    private final ObjectMapper json;

    public BrandPromotionCostServiceImpl(@Qualifier("transactionManager") PlatformTransactionManager manager,
            BrandPromotionScopePort organization, ActivityCommandDeduplicator commands, ObjectMapper json) {
        if (!(manager instanceof DataSourceTransactionManager data)) {
            throw new IllegalStateException("Core JDBC transaction required");
        }
        db = new JdbcTemplate(Objects.requireNonNull(data.getDataSource()));
        this.organization = organization;
        this.commands = commands;
        this.json = json;
    }

    @Override @Transactional
    public RuleView saveRule(ActivityActor actor, SaveRule request, String key) {
        scope(actor);
        String no = number("PCR");
        return commands.execute(actor, "BRAND_COST_RULE_SAVE", key, payload(actor, request), no, RuleView.class, () -> {
            db.update("INSERT INTO pmt_brand_cost_rule(tenant_no,management_no,brand_no,issuer_entity_no,"
                    + "rule_version_no,title,currency,headquarters_bp,store_bp,status,revision,created_at,created_by) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)", actor.tenantNo(), actor.managementNo(), actor.brandNo(),
                    actor.entityNo(), no, request.title(), request.currency(), request.headquartersBasisPoints(),
                    request.storeBasisPoints(), now(), actor.actorNo());
            return rule(actor, no, false);
        });
    }

    @Override @Transactional
    public RuleView publishRule(ActivityActor actor, String no, long revision, String key) {
        scope(actor);
        return commands.execute(actor, "BRAND_COST_RULE_PUBLISH", key, payload(actor, List.of(no, revision)), no,
                RuleView.class, () -> {
                    var row = rule(actor, no, true);
                    if (!"DRAFT".equals(row.status()) || row.revision() != revision || !"CNY".equals(row.currency())) conflict();
                    changed(db.update("UPDATE pmt_brand_cost_rule SET status='PUBLISHED',revision=revision+1,"
                            + "published_at=? WHERE tenant_no=? AND rule_version_no=? AND revision=? AND status='DRAFT'",
                            now(), actor.tenantNo(), no, revision));
                    return rule(actor, no, false);
                });
    }

    @Override public List<RuleView> listRules(ActivityActor actor) {
        scope(actor);
        return db.queryForList("SELECT * FROM pmt_brand_cost_rule WHERE tenant_no=? AND management_no=? "
                + "AND brand_no=? AND issuer_entity_no=? ORDER BY id DESC", owner(actor)).stream()
                .map(this::ruleView).toList();
    }

    @Override public void validate(ActivityActor actor, String no, List<String> stores) {
        var brand = scope(actor);
        var rule = rule(actor, no, false);
        if (!"PUBLISHED".equals(rule.status()) || !"CNY".equals(rule.currency()) || stores.isEmpty()
                || stores.stream().distinct().count() != stores.size()
                || actor.allowedStores() == null || !actor.allowedStores().containsAll(stores)
                || !brand.stores().stream().map(BrandPromotionScopePort.Store::storeNo).toList().containsAll(stores)) {
            conflict();
        }
    }

    @Override @Transactional
    public void freeze(ActivityActor actor, String no, String type, String resource, String version, List<String> stores) {
        var existing = snapshots(actor.tenantNo(), type, version);
        if (!existing.isEmpty()) {
            var old = existing.getFirst();
            if (!actor.managementNo().equals(text(old, "management_no"))
                    || !actor.brandNo().equals(text(old, "brand_no"))
                    || !actor.entityNo().equals(text(old, "issuer_entity_no"))
                    || !no.equals(text(old, "rule_version_no")) || !resource.equals(text(old, "resource_no"))
                    || !frozenStores(old).stream().map(Store::storeNo).sorted().toList()
                    .equals(stores.stream().sorted().toList())) conflict();
            return;
        }
        validate(actor, no, stores);
        if (!List.of("ACTIVITY", "COUPON").contains(type)) conflict();
        var brand = scope(actor);
        var rule = rule(actor, no, false);
        var fixed = brand.stores().stream().filter(s -> stores.contains(s.storeNo()))
                .map(s -> new Store(s.storeNo(), s.entityNo())).sorted(Comparator.comparing(Store::storeNo)).toList();
        String serialized = json.writeValueAsString(fixed);
        db.update("INSERT INTO pmt_brand_cost_snapshot(tenant_no,management_no,brand_no,issuer_entity_no,resource_type,"
                + "resource_no,resource_version,rule_version_no,currency,headquarters_bp,store_bp,stores_json,"
                + "snapshot_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id", actor.tenantNo(), actor.managementNo(),
                actor.brandNo(), actor.entityNo(), type, resource, version, no, rule.currency(),
                rule.headquartersBasisPoints(), rule.storeBasisPoints(), serialized,
                ActivityCommandDeduplicator.hash(json.writeValueAsString(List.of(type, resource, version, rule, fixed))), now());
        var stored=snapshots(actor.tenantNo(),type,version).getFirst();
        String expected=ActivityCommandDeduplicator.hash(json.writeValueAsString(List.of(type,resource,version,rule,fixed)));
        if(!expected.equals(text(stored,"snapshot_hash")))conflict();
    }

    @Override @Transactional
    public void captureActivity(String tenant, String apply, String version) {
        var frozen = snapshots(tenant, "ACTIVITY", version);
        if (frozen.isEmpty()) {
            long branded=db.queryForObject("SELECT COUNT(*) FROM pmt_apply a JOIN pmt_brand_activity_owner b "
                    + "ON b.tenant_no=a.tenant_no AND b.activity_no=a.promo_no WHERE a.tenant_no=? AND a.apply_no=?",
                    Long.class,tenant,apply);
            if(branded!=0)conflict();
            return; // Ordinary single-entity activities have no brand funding agreement.
        }
        var rows = db.queryForList("SELECT * FROM pmt_apply WHERE tenant_no=? AND apply_no=? AND promo_type='ACTIVITY'",
                tenant, apply);
        if (rows.size() != 1) conflict();
        var row = rows.getFirst();
        if(!text(frozen.getFirst(),"resource_no").equals(text(row,"promo_no")))conflict();
        var items = db.queryForList("SELECT id,qty,amount FROM ord_item WHERE tenant_no=? AND sub_order_no=? "
                + "AND deleted=0 ORDER BY id", tenant, text(row, "sub_order_no"));
        long amount = value(row, "amount_minor");
        var parts = CouponCalculation.allocate(amount, items.stream().map(i -> value(i, "amount")).toList());
        var basis = new ArrayList<Line>();
        for (int i = 0; i < items.size(); i++) {
            basis.add(new Line(value(items.get(i), "id"), (int)value(items.get(i), "qty"), parts.get(i)));
        }
        capture(tenant, "ACTIVITY", apply, version, text(row, "order_no"), text(row, "sub_order_no"),
                text(row, "entity_no"), text(row, "store_no"), amount, basis, frozen.getFirst());
    }

    @Override @Transactional
    public void captureCoupon(String tenant, String operation, UseFact fact) {
        if (!"BRAND_STORES".equals(fact.rule().redemptionScope().mode())) return;
        String source = fact.rule().redemptionScope().refs().getFirst();
        var versions = db.queryForList("SELECT native_version_no FROM pmt_brand_coupon_source WHERE tenant_no=? "
                + "AND source_no=? AND status IN ('ACTIVE','STOPPED')", tenant, source);
        if (versions.size() != 1) conflict();
        String version = text(versions.getFirst(), "native_version_no");
        var fixed = snapshots(tenant, "COUPON", version);
        if (fixed.size() != 1) conflict();
        var c = fact.command();
        var subs = db.queryForList("SELECT order_no FROM ord_sub_order WHERE tenant_no=? AND sub_order_no=? "
                + "AND entity_no=? AND store_no=?", tenant, c.sourceRef().sourceNo(), c.entityNo(), c.storeNo());
        if (subs.size() != 1) conflict();
        var basis = new ArrayList<Line>();
        for (var allocation : fact.couponAllocations()) {
            if ("__FREIGHT__".equals(allocation.orderLineNo())) {
                basis.add(new Line(0, 1, allocation.discountMinor()));
            } else {
                long id = Long.parseLong(allocation.orderLineNo().substring(2));
                var rows = db.queryForList("SELECT qty FROM ord_item WHERE tenant_no=? AND id=? AND sub_order_no=?",
                        tenant, id, c.sourceRef().sourceNo());
                if (rows.size() != 1) conflict();
                basis.add(new Line(id, (int)value(rows.getFirst(), "qty"), allocation.discountMinor()));
            }
        }
        var originalItems=db.queryForList("SELECT id,qty FROM ord_item WHERE tenant_no=? AND sub_order_no=?",
                tenant,c.sourceRef().sourceNo());
        for(var item:originalItems) {
            long id=value(item,"id");
            if(basis.stream().noneMatch(line->line.itemId()==id))basis.add(new Line(id,(int)value(item,"qty"),0));
        }
        capture(tenant, "COUPON", operation, version, text(subs.getFirst(), "order_no"), c.sourceRef().sourceNo(),
                c.entityNo(), c.storeNo(), c.amountMinor(), basis, fixed.getFirst());
    }

    private void capture(String tenant, String type, String no, String version, String order, String sub,
            String entity, String store, long amount, List<Line> basis, Map<String,Object> snapshot) {
        if (!frozenStores(snapshot).contains(new Store(store, entity)) || amount <= 0
                || basis.stream().mapToLong(Line::costMinor).reduce(0, Math::addExact) != amount) conflict();
        var orderRows=db.queryForList("SELECT currency FROM ord_order WHERE tenant_no=? AND order_no=?",tenant,order);
        if(orderRows.size()!=1 || !text(snapshot,"currency").equals(text(orderRows.getFirst(),"currency")))conflict();
        String content = json.writeValueAsString(Arrays.asList(type,no,version,order,sub,entity,store,amount,basis));
        String hash = ActivityCommandDeduplicator.hash(content);
        db.update("INSERT INTO pmt_brand_cost_source(tenant_no,source_type,source_no,resource_version,order_no,"
                + "sub_order_no,consumer_entity_no,store_no,total_minor,basis_json,payload_hash,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id", tenant,type,no,version,order,sub,
                entity,store,amount,json.writeValueAsString(basis),hash,now());
        String original = db.queryForObject("SELECT payload_hash FROM pmt_brand_cost_source WHERE tenant_no=? "
                + "AND source_type=? AND source_no=?", String.class,tenant,type,no);
        if (!hash.equals(original)) conflict();
    }

    @Override @Transactional
    public StatementView generate(ActivityActor actor, String expense, GenerateStatement request, String key) {
        scope(actor);
        String no = number("PCS");
        return commands.execute(actor,"BRAND_COST_GENERATE",key,payload(actor,List.of(expense,request)),no,
                StatementView.class,() -> {
                    synchronize(actor, request.throughAt());
                    expense(actor,expense);
                    // The rule row serializes all statement generation for this brand and expense scope.
                    db.queryForList("SELECT id FROM pmt_brand_cost_rule WHERE tenant_no=? AND management_no=? "
                            + "AND brand_no=? AND issuer_entity_no=? ORDER BY id FOR UPDATE", owner(actor));
                    var facts = available(actor,expense,request.currency(),request.throughAt());
                    if (facts.isEmpty()) conflict();
                    long total=0,hq=0,store=0;
                    for (var fact:facts) { total=Math.addExact(total,value(fact,"amount_minor"));
                        hq=Math.addExact(hq,value(fact,"headquarters_minor"));
                        store=Math.addExact(store,value(fact,"store_minor")); }
                    db.update("INSERT INTO pmt_brand_cost_statement(tenant_no,management_no,brand_no,issuer_entity_no,"
                            + "expense_entity_no,statement_no,currency,through_at,status,revision,total_minor,"
                            + "headquarters_minor,store_minor,created_at,created_by) VALUES(?,?,?,?,?,?,?,?,'DRAFT',0,?,?,?,?,?)",
                            actor.tenantNo(),actor.managementNo(),actor.brandNo(),actor.entityNo(),expense,no,
                            request.currency(),Timestamp.from(request.throughAt()),total,hq,store,now(),actor.actorNo());
                    for(var fact:facts) changed(db.update("UPDATE pmt_brand_cost_fact SET statement_no=? WHERE id=? "
                            + "AND tenant_no=? AND statement_no IS NULL",no,value(fact,"id"),actor.tenantNo()));
                    return get(actor,expense,no);
                });
    }

    @Override public List<StatementView> list(ActivityActor actor,String expense) {
        scope(actor); expense(actor,expense);
        var args = new ArrayList<Object>(Arrays.asList(owner(actor))); args.add(expense);
        return db.queryForList("SELECT statement_no FROM pmt_brand_cost_statement WHERE tenant_no=? "
                + "AND management_no=? AND brand_no=? AND issuer_entity_no=? AND expense_entity_no=? ORDER BY id DESC",
                args.toArray()).stream().map(r -> get(actor,expense,text(r,"statement_no"))).toList();
    }

    @Override public StatementView get(ActivityActor actor,String expense,String no) {
        scope(actor); expense(actor,expense);
        var rows=db.queryForList("SELECT * FROM pmt_brand_cost_statement WHERE tenant_no=? AND management_no=? "
                + "AND brand_no=? AND issuer_entity_no=? AND expense_entity_no=? AND statement_no=?",
                actor.tenantNo(),actor.managementNo(),actor.brandNo(),actor.entityNo(),expense,no);
        if(rows.size()!=1) throw BizException.of(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();
        var facts=db.queryForList("SELECT f.*,s.resource_version,s.order_no,s.store_no,s.consumer_entity_no,"
                + "p.rule_version_no FROM pmt_brand_cost_fact f JOIN pmt_brand_cost_source s ON s.tenant_no=f.tenant_no "
                + "AND s.source_type=f.source_type AND s.source_no=f.source_no JOIN pmt_brand_cost_snapshot p "
                + "ON p.tenant_no=s.tenant_no AND p.resource_type=s.source_type AND p.resource_version=s.resource_version "
                + "WHERE f.tenant_no=? AND f.statement_no=? AND f.expense_entity_no=? "
                + "AND p.management_no=? AND p.brand_no=? AND p.issuer_entity_no=? AND p.currency=? ORDER BY f.id",
                actor.tenantNo(),no,expense,actor.managementNo(),actor.brandNo(),actor.entityNo(),text(row,"currency")).stream().map(r -> new FactView("PCF"+value(r,"id"),text(r,"source_type"),
                        text(r,"source_no"),text(r,"event_no"),text(r,"event_kind"),text(r,"resource_version"),
                        text(r,"order_no"),text(r,"store_no"),text(r,"consumer_entity_no"),text(r,"rule_version_no"),
                        value(r,"amount_minor"),value(r,"headquarters_minor"),value(r,"store_minor"),time(r,"occurred_at")))
                .toList();
        if ("VOID".equals(text(row,"status"))) {
            facts=List.of(json.readValue(text(row,"facts_json"),FactView[].class));
        }
        long difference=Math.subtractExact(value(row,"total_minor"),facts.stream().mapToLong(FactView::amountMinor)
                .reduce(0,Math::addExact));
        difference=Math.addExact(Math.abs(difference),Math.addExact(
                Math.abs(Math.subtractExact(value(row,"headquarters_minor"),facts.stream()
                        .mapToLong(FactView::headquartersMinor).reduce(0,Math::addExact))),
                Math.abs(Math.subtractExact(value(row,"store_minor"),facts.stream()
                        .mapToLong(FactView::storeMinor).reduce(0,Math::addExact)))));
        return new StatementView(no,expense,text(row,"currency"),text(row,"status"),value(row,"revision"),
                value(row,"total_minor"),value(row,"headquarters_minor"),value(row,"store_minor"),difference,
                time(row,"through_at"),time(row,"created_at"),time(row,"confirmed_at"),facts);
    }

    @Override @Transactional
    public StatementView confirm(ActivityActor a,String e,String no,RevisionCommand r,String key) {
        return transition(a,e,no,r,key,true);
    }
    @Override @Transactional
    public StatementView voidStatement(ActivityActor a,String e,String no,RevisionCommand r,String key) {
        return transition(a,e,no,r,key,false);
    }
    private StatementView transition(ActivityActor a,String e,String no,RevisionCommand r,String key,boolean confirm) {
        scope(a); expense(a,e);
        return commands.execute(a,confirm?"BRAND_COST_CONFIRM":"BRAND_COST_VOID",key,
                payload(a,List.of(e,no,r)),no,StatementView.class,() -> {
                    db.queryForList("SELECT id FROM pmt_brand_cost_statement WHERE tenant_no=? AND statement_no=? "
                            + "FOR UPDATE",a.tenantNo(),no);
                    var before=get(a,e,no);
                    if(!"DRAFT".equals(before.status()) || before.revision()!=r.expectedRevision()
                            || before.discrepancyMinor()!=0 || before.facts().isEmpty()) conflict();
                    changed(db.update("UPDATE pmt_brand_cost_statement SET status=?,revision=revision+1,confirmed_at=?,facts_json=? "
                            + "WHERE tenant_no=? AND statement_no=? AND revision=? AND status='DRAFT'",
                            confirm?"CONFIRMED":"VOID",confirm?now():null,json.writeValueAsString(before.facts()),
                            a.tenantNo(),no,r.expectedRevision()));
                    if(!confirm)db.update("UPDATE pmt_brand_cost_fact SET statement_no=NULL WHERE tenant_no=? AND statement_no=?",
                            a.tenantNo(),no);
                    return get(a,e,no);
                });
    }

    private void synchronize(ActivityActor actor,Instant through) {
        var args=owner(actor);
        var sources=db.queryForList("SELECT s.*,p.issuer_entity_no,p.currency,p.headquarters_bp,p.store_bp "
                + "FROM pmt_brand_cost_source s JOIN pmt_brand_cost_snapshot p ON p.tenant_no=s.tenant_no "
                + "AND p.resource_type=s.source_type AND p.resource_version=s.resource_version WHERE p.tenant_no=? "
                + "AND p.management_no=? AND p.brand_no=? AND p.issuer_entity_no=? ORDER BY s.id FOR UPDATE",args);
        for(var source:sources) {
            var orders=db.queryForList("SELECT paid_at FROM ord_order WHERE tenant_no=? AND order_no=? "
                    + "AND paid_at IS NOT NULL AND paid_at<=?",actor.tenantNo(),text(source,"order_no"),through.toEpochMilli());
            if(orders.isEmpty())continue;
            var original=PromotionCostShares.allocate(value(source,"total_minor"),(int)value(source,"headquarters_bp"),
                    (int)value(source,"store_bp"));
            recordFact(source,"PAID"+ActivityCommandDeduplicator.hash(text(source,"order_no")).substring(0,60),"PAID",original,
                    Instant.ofEpochMilli(value(orders.getFirst(),"paid_at")));
            var refunds=db.queryForList("SELECT execution_no,plan_json,succeeded_at FROM ord_refund_execution "
                    + "WHERE tenant_no=? AND sub_order_no=? AND status='SUCCEEDED' AND succeeded_at<=? "
                    + "ORDER BY succeeded_at,id",actor.tenantNo(),text(source,"sub_order_no"),through.toEpochMilli());
            var basis=List.of(json.readValue(text(source,"basis_json"),Line[].class));
            var state=text(source,"refund_state_json")==null ? new RefundState(Map.of(),List.of(),0)
                    : json.readValue(text(source,"refund_state_json"),RefundState.class);
            Map<Long,Long> quantities=new HashMap<>(state.quantities());
            var processed=new ArrayList<>(state.processed());
            long before=state.refundedMinor();
            for(var refund:refunds) {
                if(processed.contains(text(refund,"execution_no")))continue;
                var plan=json.readValue(text(refund,"plan_json"),RefundPlan.class);
                if(!text(source,"sub_order_no").equals(plan.subOrderNo()))conflict();
                for(var line:plan.lines())quantities.merge(line.itemId(),(long)line.qty(),Math::addExact);
                boolean fullyReturned=basis.stream().filter(l->l.itemId()!=0)
                        .allMatch(l->quantities.getOrDefault(l.itemId(),0L)>=l.quantity());
                long after=0;
                for(var line:basis) {
                    long reversal=line.itemId()==0 ? (fullyReturned?line.costMinor():0)
                            : BigInteger.valueOf(line.costMinor())
                            .multiply(BigInteger.valueOf(Math.min(line.quantity(),quantities.getOrDefault(line.itemId(),0L))))
                            .divide(BigInteger.valueOf(line.quantity())).longValueExact();
                    after=Math.addExact(after,reversal);
                }
                var reversed=PromotionCostShares.reverse(original,before,after);
                recordFact(source,text(refund,"execution_no"),"REFUND",reversed,
                        Instant.ofEpochMilli(value(refund,"succeeded_at")));
                before=after;
                processed.add(text(refund,"execution_no"));
                db.update("UPDATE pmt_brand_cost_source SET refund_state_json=? WHERE tenant_no=? AND id=?",
                        json.writeValueAsString(new RefundState(quantities,processed,after)),actor.tenantNo(),
                        value(source,"id"));
            }
        }
    }

    private void recordFact(Map<String,Object> source,String event,String kind,PromotionCostShares.Shares shares,Instant at) {
        String hq=text(source,"issuer_entity_no"),store=text(source,"consumer_entity_no");
        if(hq.equals(store)) insertFact(source,event,kind,hq,shares,at);
        else {
            insertFact(source,event,kind,hq,new PromotionCostShares.Shares(shares.headquartersMinor(),0),at);
            insertFact(source,event,kind,store,new PromotionCostShares.Shares(0,shares.storeMinor()),at);
        }
    }
    private void insertFact(Map<String,Object>s,String event,String kind,String entity,
                            PromotionCostShares.Shares shares,Instant at) {
        if(shares.totalMinor()==0)return;
        db.update("INSERT INTO pmt_brand_cost_fact(tenant_no,source_type,source_no,event_no,event_kind,expense_entity_no,"
                + "amount_minor,headquarters_minor,store_minor,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE id=id",text(s,"tenant_no"),text(s,"source_type"),text(s,"source_no"),
                event,kind,entity,shares.totalMinor(),shares.headquartersMinor(),shares.storeMinor(),Timestamp.from(at));
    }
    private List<Map<String,Object>> available(ActivityActor a,String expense,String currency,Instant through) {
        return db.queryForList("SELECT f.* FROM pmt_brand_cost_fact f JOIN pmt_brand_cost_source s ON s.tenant_no=f.tenant_no "
                + "AND s.source_type=f.source_type AND s.source_no=f.source_no JOIN pmt_brand_cost_snapshot p "
                + "ON p.tenant_no=s.tenant_no AND p.resource_type=s.source_type AND p.resource_version=s.resource_version "
                + "WHERE p.tenant_no=? AND p.management_no=? AND p.brand_no=? AND p.issuer_entity_no=? "
                + "AND f.expense_entity_no=? AND p.currency=? AND f.statement_no IS NULL AND f.occurred_at<=? ORDER BY f.id",
                a.tenantNo(),a.managementNo(),a.brandNo(),a.entityNo(),expense,currency,Timestamp.from(through));
    }
    private BrandPromotionScopePort.Brand scope(ActivityActor a) {
        if(a.brandNo()==null || a.managementNo()==null)throw BizException.of(ErrorCode.FORBIDDEN);
        var brand=organization.find(a.tenantNo(),a.managementNo(),a.brandNo(),a.entityNo());
        if(brand==null || !a.managementNo().equals(brand.managementNo()) || !a.brandNo().equals(brand.brandNo())
                || !a.entityNo().equals(brand.legalEntityNo()))throw BizException.of(ErrorCode.FORBIDDEN);
        return brand;
    }
    private void expense(ActivityActor a,String entity) {
        if(a.entityNo().equals(entity) || scope(a).stores().stream().anyMatch(s -> entity.equals(s.entityNo())))return;
        var rows=db.queryForList("SELECT stores_json FROM pmt_brand_cost_snapshot WHERE tenant_no=? AND management_no=? "
                + "AND brand_no=? AND issuer_entity_no=?",owner(a));
        if(rows.stream().flatMap(r->frozenStores(r).stream()).noneMatch(s->entity.equals(s.entityNo()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
    private RuleView rule(ActivityActor a,String no,boolean lock) {
        var rows=db.queryForList("SELECT * FROM pmt_brand_cost_rule WHERE tenant_no=? AND management_no=? AND brand_no=? "
                + "AND issuer_entity_no=? AND rule_version_no=?"+(lock?" FOR UPDATE":""),
                a.tenantNo(),a.managementNo(),a.brandNo(),a.entityNo(),no);
        if(rows.size()!=1)throw BizException.of(ErrorCode.NOT_FOUND);
        return ruleView(rows.getFirst());
    }
    private RuleView ruleView(Map<String,Object>r) {
        return new RuleView(text(r,"rule_version_no"),text(r,"title"),text(r,"currency"),
                (int)value(r,"headquarters_bp"),(int)value(r,"store_bp"),text(r,"status"),value(r,"revision"),
                time(r,"created_at"),time(r,"published_at"));
    }
    private List<Map<String,Object>> snapshots(String tenant,String type,String version) {
        return db.queryForList("SELECT * FROM pmt_brand_cost_snapshot WHERE tenant_no=? AND resource_type=? "
                + "AND resource_version=?",tenant,type,version);
    }
    private List<Store> frozenStores(Map<String,Object>row) {
        return List.of(json.readValue(text(row,"stores_json"),Store[].class));
    }
    private static Object[] owner(ActivityActor a) {
        return new Object[]{a.tenantNo(),a.managementNo(),a.brandNo(),a.entityNo()};
    }
    private static Object payload(ActivityActor a,Object command) {
        return List.of(a.managementNo(),a.brandNo(),command);
    }
    private static long value(Map<String,Object> row,String column) { return ((Number)row.get(column)).longValue(); }
    private static String text(Map<String,Object> row,String column) { return (String)row.get(column); }
    private static Instant time(Map<String,Object> row,String column) {
        Object time=row.get(column);
        if(time==null)return null;
        if(time instanceof Timestamp timestamp)return timestamp.toInstant();
        return ((LocalDateTime)time).toInstant(ZoneOffset.UTC);
    }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static String number(String prefix) { return prefix+UUID.randomUUID().toString().replace("-",""); }
    private static void changed(int count) { if(count!=1)conflict(); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    /** Arrival order is fixed under the source lock, including zero-rounded successful refunds. */
    public record RefundState(Map<Long,Long> quantities,List<String> processed,long refundedMinor) { }
    public record Store(String storeNo,String entityNo) { }
    public record Line(long itemId,int quantity,long costMinor) { }
}
