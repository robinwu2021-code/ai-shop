package ai.neargo.shop.trade.order.printing.service.impl;

import ai.neargo.shop.trade.order.printing.*;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueReceiptConfirmed;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

/** Immutable owner facts only. No balance query, no historical backfill from current order status. */
@Service
public class BusinessPrintFactServiceImpl implements BusinessPrintFactService {
    private final BusinessPrintFactMapper rows;
    private final PaymentMapper orders;
    private final OrderItemMapper items;
    private final ObjectMapper json;
    private final OutboxEventBus events;
    public BusinessPrintFactServiceImpl(BusinessPrintFactMapper rows, PaymentMapper orders, OrderItemMapper items,
                                   ObjectMapper json, OutboxEventBus events) {
        this.rows=rows; this.orders=orders; this.items=items; this.json=json; this.events=events;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public PrintFactRef recordRecharge(StoredValueReceiptConfirmed e) {
        PayEntitlementEventCodec.validate(e);
        var fields=new LinkedHashMap<String,Object>();
        fields.put("rechargeNo",e.rechargeNo());
        fields.put("accountNo",e.accountNo());
        fields.put("principalAmount",money(e.principalAmountMinor(),e.currency()));
        fields.put("giftAmount",money(e.giftAmountMinor(),e.currency()));
        fields.put("balanceAfter",money(e.balanceAfterMinor(),e.currency()));
        return save(e.tenantNo(),e.entityNo(),e.storeNo(),"RECHARGE",e.rechargeNo(),null,null,
                "RECHARGE_RECEIPT","RECHARGE_CONFIRMED",Instant.ofEpochMilli(e.occurredAt()),
                e.currency(),fields,List.of(),json.writeValueAsString(e),List.of());
    }

    /** Called only after the owner has completed cash and all benefit obligations, in its transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public PrintFactRef recordRefund(OrdAfterSale sale, OrdSubOrder sub, OrdRefundExecution execution) {
        if (!OrdAfterSale.REFUNDED.equals(sale.getStatus()) || execution==null
                || !OrdRefundExecution.SUCCEEDED.equals(execution.getStatus())
                || !Integer.valueOf(2).equals(execution.getClosureVersion())
                || !Objects.equals(sale.getAfterSaleNo(),execution.getAfterSaleNo())
                || !Objects.equals(sale.getSubOrderNo(),sub.getSubOrderNo())
                || !Objects.equals(sub.getSubOrderNo(),execution.getSubOrderNo())
                || !Objects.equals(sale.getEntityNo(),sub.getEntityNo()))
            throw new IllegalArgumentException("Refund receipt requires the owner's terminal proof");
        return freezeRefund(sale,sub,new RefundReceiptProof(execution.getExecutionNo(),execution.getCurrency(),
                execution.getOriginPaymentNo(),execution.getPayChannel(),execution.getAmountMinor(),
                execution.getPaymentNo(),execution.getSucceededAt(),execution.getPlanJson()),Map.of());
    }

    /** Points owner supplies its original confirmed decision and plan, including zero-cash outcomes. */
    @Transactional(propagation=Propagation.MANDATORY)
    public PrintFactRef recordPointsRefund(OrdAfterSale sale,OrdSubOrder sub,RefundPlan plan,
            ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Binding binding) {
        if (!OrdAfterSale.REFUNDED.equals(sale.getStatus()) || binding==null || binding.command()==null
                || binding.preparation()==null || binding.outcome()==null || !"CONFIRMED".equals(binding.outcome().state())
                || !Set.of(ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.POINTS_CONFIRMED,
                        ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.COMMITTED).contains(binding.phase())
                || !Objects.equals(sale.getAfterSaleNo(),binding.afterSaleNo())
                || !Objects.equals(sale.getSubOrderNo(),binding.subOrderNo())
                || !Objects.equals(sub.getSubOrderNo(),binding.subOrderNo())
                || !Objects.equals(sub.getEntityNo(),sale.getEntityNo()))
            throw new IllegalArgumentException("Points receipt requires the owner's terminal proof");
        var payment=binding.command().payment();
        return freezeRefund(sale,sub,new RefundReceiptProof("PRF-"+sale.getAfterSaleNo(),binding.command().currency(),
                payment==null?null:payment.outTradeNo(),payment==null?null:payment.channel(),
                binding.preparation().netCashMinor(),binding.cashPaymentNo(),sale.getRefundedAt(),json.writeValueAsString(plan)),
                Map.of("returnedPoints",binding.outcome().returnedPoints(),"reclaimedPoints",binding.outcome().reclaimedPoints(),
                        "cashOffsetMinor",binding.preparation().cashOffsetMinor()));
    }
    private record RefundReceiptProof(String getExecutionNo,String getCurrency,String getOriginPaymentNo,
            String getPayChannel,Long getAmountMinor,String getPaymentNo,Long getSucceededAt,String getPlanJson) {}

    private PrintFactRef freezeRefund(OrdAfterSale sale,OrdSubOrder sub,RefundReceiptProof execution,Map<String,Object> benefits) {
        var order=orders.selectOne(new QueryWrapper<OrdOrder>().eq("order_no",sub.getOrderNo())
                .eq("tenant_no",sub.getTenantNo()));
        if (order==null || !Objects.equals(order.getCurrency(),execution.getCurrency()))
            throw new IllegalArgumentException("Refund currency or order binding mismatch");
        var diagnostics=new ArrayList<String>();
        var fields=new LinkedHashMap<String,Object>();
        fields.put("refundNo",sale.getAfterSaleNo());
        fields.put("originalPaymentNo",execution.getOriginPaymentNo());
        fields.put("refundedAmount",money(execution.getAmountMinor(),execution.getCurrency()));
        fields.put("refundChannel",execution.getAmountMinor()==0 ? "NO_CASH" : execution.getPayChannel());
        fields.put("refundReason",sale.getReason());
        fields.put("refundPaymentNo",execution.getPaymentNo());
        fields.put("refundMode",sale.getRefundMode());
        fields.put("pointsOffsetMinor",sale.getPointsOffsetMinor());
        fields.putAll(benefits);
        if (execution.getOriginPaymentNo()==null || execution.getOriginPaymentNo().isBlank()
                || sale.getReason()==null || execution.getSucceededAt()==null
                || execution.getAmountMinor()>0 && (execution.getPayChannel()==null || execution.getPaymentNo()==null))
            diagnostics.add("print.sourceRefundEvidenceMissing");
        var plan=json.readValue(execution.getPlanJson(),RefundPlan.class);
        var lines=new ArrayList<Map<String,Object>>();
        long sum=0;
        for (var l:plan.lines()) {
            var item=items.selectOne(new QueryWrapper<OrdItem>().eq("id",l.itemId())
                    .eq("order_no",sub.getOrderNo()).eq("sub_order_no",sub.getSubOrderNo()));
            if(item==null || item.getTitle()==null) { diagnostics.add("print.sourceRefundLineMissing"); continue; }
            lines.add(Map.of("lineRef",String.valueOf(l.itemId()),"title",item.getTitle(),
                    "quantity",String.valueOf(l.qty()),"unit","件","refundAmount",money(l.amountMinor(),execution.getCurrency())));
            sum=Math.addExact(sum,l.amountMinor());
        }
        if(plan.freightMinor()!=0) {
            lines.add(Map.of("lineRef","FREIGHT","title","退运费","quantity","1","unit","项",
                    "refundAmount",money(plan.freightMinor(),execution.getCurrency())));
            sum=Math.addExact(sum,plan.freightMinor());
        }
        // A cash offset must never silently be presented as the unadjusted goods refund.
        if(sum!=execution.getAmountMinor() || lines.isEmpty() || lines.size()>500)
            diagnostics.add("print.sourceRefundCompositionUnsupported");
        var owner=new LinkedHashMap<String,Object>();
        owner.put("executionNo",execution.getExecutionNo()); owner.put("plan",plan); owner.put("fields",fields);
        owner.put("currency",execution.getCurrency()); owner.put("succeededAt",execution.getSucceededAt());
        return save(sub.getTenantNo(),sub.getEntityNo(),sub.getStoreNo(),"REFUND",sale.getAfterSaleNo(),
                sub.getOrderNo(),sub.getSubOrderNo(),"REFUND_RECEIPT","REFUND_CONFIRMED",
                execution.getSucceededAt()==null ? Instant.EPOCH:Instant.ofEpochMilli(execution.getSucceededAt()),
                execution.getCurrency(),fields,lines,json.writeValueAsString(owner),diagnostics);
    }

    private PrintFactRef save(String tenant,String entity,String store,String type,String no,String order,String sub,
            String scene,String trigger,Instant at,String currency,Map<String,Object> fields,
            List<Map<String,Object>> lines,String owner,List<String> diagnostics) {
        if(!id(tenant)||tenant.length()>32||!id(entity)||!id(store)||!id(no)) throw new IllegalArgumentException("Receipt scope is incomplete");
        String key=PrintHashes.sha256(type+":"+no), ownerHash=PrintHashes.sha256(json.writeValueAsString(sorted(json.readTree(owner))));
        var old=rows.selectOne(scoped(new PrintScope(tenant,entity,store)).eq("source_fact_key",key));
        if(old!=null) {
            if(!ownerHash.equals(old.getOwnerEventHash())) throw new IllegalStateException("Receipt owner replay conflict");
            return ref(old);
        }
        var f=new BusinessPrintFact();
        f.setTenantNo(tenant); f.setEntityNo(entity); f.setStoreNo(store); f.setSourceType(type); f.setSourceNo(no);
        f.setOrderNo(order); f.setSubOrderNo(sub); f.setOwnerEventHash(ownerHash);
        f.setPrintFactNo("BF"+UUID.randomUUID().toString().replace("-", "").substring(0,28));
        f.setBizRevision("1"); f.setScene(scene); f.setTriggerType(trigger); f.setSourceFactKey(key);
        f.setReceiptIntentKey(key); f.setPayloadSchemaVersion(1); f.setSourceOccurredAt(LocalDateTime.ofInstant(at,ZoneOffset.UTC));
        var payload=new LinkedHashMap<String,Object>();
        payload.put("scene",scene); payload.put("payloadSchemaVersion",1);
        // The owner freezes the actual store identity. No later mutable display-name lookup.
        payload.put("store",Map.of("name","门店编号 " + store + " · 时间 UTC","timezone","UTC"));
        payload.put("source",Map.of("type",type,"no",no,"factNo",f.getPrintFactNo(),"revision","1","occurredAt",at.toString()));
        payload.put("currency",currency); payload.put("fields",fields); payload.put("lines",lines);
        f.setSnapshot(json.writeValueAsString(sorted(json.valueToTree(payload)))); f.setSnapshotHash(PrintHashes.sha256(f.getSnapshot()));
        f.setPrintability(diagnostics.isEmpty()?"READY":"BLOCKED");
        f.setBlockCode(diagnostics.isEmpty()?null:diagnostics.getFirst());
        f.setDiagnostics(json.writeValueAsString(diagnostics)); f.setRevision(0L);
        f.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); f.setUpdatedAt(f.getCreatedAt());
        rows.insert(f); var ref=ref(f); events.publish(new BusinessPrintFactReady(no,ref)); return ref;
    }
    private tools.jackson.databind.JsonNode sorted(tools.jackson.databind.JsonNode node) {
        if(node.isObject()) {
            var out=json.createObjectNode(); var keys=new TreeSet<String>(); node.propertyNames().forEach(keys::add);
            keys.forEach(key->out.set(key,sorted(node.get(key)))); return out;
        }
        if(node.isArray()) { var out=json.createArrayNode(); node.forEach(child->out.add(sorted(child))); return out; }
        return node;
    }
    private boolean id(String s) { return s!=null&&!s.isBlank()&&s.length()<=64&&s.equals(s.strip()); }
    private Object money(long value,String currency) {
        int scale=Currency.getInstance(currency).getDefaultFractionDigits();
        if(scale<0)throw new IllegalArgumentException("Unsupported currency");
        return Map.of("minor",Long.toString(value),"currency",currency,"scale",scale);
    }
    private QueryWrapper<BusinessPrintFact> scoped(PrintScope s) {
        return new QueryWrapper<BusinessPrintFact>().eq("tenant_no",s.tenantNo()).eq("entity_no",s.entityNo()).eq("store_no",s.storeNo());
    }
    private PrintFactRef ref(BusinessPrintFact f) {
        return new PrintFactRef(f.getTenantNo(),f.getEntityNo(),f.getStoreNo(),f.getSourceType(),f.getPrintFactNo(),f.getBizRevision(),f.getScene(),f.getSnapshotHash());
    }
    public List<PrintFactRef> referencesByOrder(PrintScope s,String no) {
        return rows.selectList(scoped(s).and(w->w.eq("source_no",no).or().eq("order_no",no).or().eq("sub_order_no",no))
                .orderByAsc("id").last("LIMIT 101")).stream().map(this::ref).toList();
    }
    public PrintFactRef resolveReference(PrintSourceReference r,PrintScope scope) {
        if(!Objects.equals(scene(r.type()),r.scene()))throw new PrintFailure("print.sourceMismatch");
        var query=scoped(scope).eq("source_type",r.type())
                .eq("print_fact_no",r.factNo()).eq("biz_revision",r.revision()).eq("scene",r.scene());
        if ("REFUND".equals(r.type()))
            query.and(alias->alias.eq("source_no",r.no()).or().eq("order_no",r.no()).or().eq("sub_order_no",r.no()));
        else query.eq("source_no",r.no());
        var f=rows.selectOne(query);
        if(f==null)throw new PrintFailure("print.factNotFound"); return ref(f);
    }
    private String scene(String type) { return switch(type) { case "REFUND"->"REFUND_RECEIPT"; case "RECHARGE"->"RECHARGE_RECEIPT"; default->throw new PrintFailure("print.sourceMismatch"); }; }
    public PrintFact snapshot(PrintFactRef r) {
        var f=rows.selectOne(scoped(new PrintScope(r.tenantNo(),r.entityNo(),r.storeNo())).eq("print_fact_no",r.factNo()));
        if(f==null||!ref(f).equals(r))throw new PrintFailure("print.factNotFound");
        return new PrintFact(r,f.getOrderNo(),f.getSubOrderNo(),f.getTriggerType(),f.getSourceFactKey(),
                f.getSourceOccurredAt().toInstant(ZoneOffset.UTC),f.getPayloadSchemaVersion(),
                new FrozenJson(f.getSnapshot(),f.getSnapshotHash()),Printability.valueOf(f.getPrintability()),
                f.getBlockCode(),Arrays.asList(json.readValue(f.getDiagnostics(),String[].class)));
    }
    public PrintExecutionGuard executionGuard(PrintFactRef r) {
        var fact=snapshot(r);
        return new PrintExecutionGuard(fact.printability()==Printability.READY,
                fact.printability()==Printability.READY?GuardDecision.ALLOW_HISTORICAL_RECEIPT:GuardDecision.HOLD_UNKNOWN,
                r.bizRevision(),false,"IMMUTABLE_TERMINAL_RECEIPT");
    }
    public PrintFactPage scan(PrintFactCursor c,int limit) {
        scene(c.sourceKind());
        if(limit<1||limit>500||c.from()==null||c.to()==null||c.from().isAfter(c.to()))throw new PrintFailure("print.invalidScan");
        var q=scoped(c.scope()).eq("source_type",c.sourceKind())
                .ge("created_at",LocalDateTime.ofInstant(c.from(),ZoneOffset.UTC)).le("created_at",LocalDateTime.ofInstant(c.to(),ZoneOffset.UTC));
        if(c.watermarkAt()!=null)q.and(w->w.gt("created_at",LocalDateTime.ofInstant(c.watermarkAt(),ZoneOffset.UTC)).or(w2->w2.eq("created_at",LocalDateTime.ofInstant(c.watermarkAt(),ZoneOffset.UTC)).gt("print_fact_no",c.watermarkNo())));
        var found=rows.selectList(q.orderByAsc("created_at","print_fact_no").last("LIMIT "+limit));
        var last=found.isEmpty()?null:found.getLast();
        var next=last==null?c:new PrintFactCursor(c.scope(),c.sourceKind(),c.from(),c.to(),last.getCreatedAt().toInstant(ZoneOffset.UTC),last.getPrintFactNo());
        return new PrintFactPage(found.stream().map(this::ref).toList(),next,found.size()==limit);
    }
}
