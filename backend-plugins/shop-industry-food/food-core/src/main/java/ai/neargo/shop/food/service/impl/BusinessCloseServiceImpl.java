package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.Issue;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.entity.FnbCloseAdjustment;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbCloseException;
import ai.neargo.shop.food.entity.FnbCloseRun;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbCloseBalance;
import ai.neargo.shop.food.entity.FnbSettlementItem;
import ai.neargo.shop.food.entity.FnbBusinessDay;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseRunMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseBalanceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseExceptionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseAdjustmentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** V1 day-close evidence. Serializability protects first-day insertion and fact-range reads. */
public class BusinessCloseServiceImpl implements BusinessCloseService {
    private final BusinessDayMapper days;
    private final CloseRunMapper runs;
    private final CloseBalanceMapper balances;
    private final CloseExceptionMapper exceptions;
    private final CloseAdjustmentMapper adjustments;
    private final StorePolicyMapper policies;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final SettlementMapper settlements;
    private final SettlementItemMapper settlementItems;
    private final CoreOrderBindingMapper bindings;
    private final ReturnMapper returns;
    private final KitchenTicketMapper tickets;
    private final KitchenTaskMapper tasks;
    private final ServiceEventMapper events;
    private final StoreCardQueryPort stores;
    private final IdempotencyService idem;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public BusinessCloseServiceImpl(BusinessDayMapper days, CloseRunMapper runs, CloseBalanceMapper balances,
            CloseExceptionMapper exceptions, CloseAdjustmentMapper adjustments, StorePolicyMapper policies,
            OrderBatchMapper batches, OrderLineMapper lines, SettlementMapper settlements, SettlementItemMapper settlementItems,
            CoreOrderBindingMapper bindings, ReturnMapper returns, KitchenTicketMapper tickets, KitchenTaskMapper tasks,
            ServiceEventMapper events,
            StoreCardQueryPort stores, IdempotencyService idem, ObjectMapper json, PlatformTransactionManager manager) {
        this.days=days; this.runs=runs; this.balances=balances; this.exceptions=exceptions;
        this.adjustments=adjustments; this.policies=policies; this.batches=batches; this.lines=lines;
        this.settlements=settlements; this.settlementItems=settlementItems; this.bindings=bindings;
        this.returns=returns; this.tickets=tickets; this.tasks=tasks; this.events=events;
        this.stores=stores; this.idem=idem; this.json=json;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override public DayVO open(String entity,String store,String actor,LocalDate date,String key) {
        scope(entity,store,actor); date(date);
        return write(entity,store,actor,key,"OPEN_DAY",date.toString(),DayVO.class,() -> {
            var existing=days.selectList(Wrappers.<FnbBusinessDay>lambdaQuery()
                    .eq(FnbBusinessDay::getEntityNo,entity).eq(FnbBusinessDay::getStoreNo,store)
                    .eq(FnbBusinessDay::getBusinessDate,date).last("FOR UPDATE"));
            if(!existing.isEmpty()) return day(existing.getFirst());
            // Freeze policy already effective at the beginning of this local business day.
            var candidates=policies.selectList(Wrappers.<FnbStorePolicy>lambdaQuery()
                    .eq(FnbStorePolicy::getEntityNo,entity).eq(FnbStorePolicy::getStoreNo,store)
                    .orderByDesc(FnbStorePolicy::getEffectiveFrom).orderByDesc(FnbStorePolicy::getPolicyNo)
                    .last("FOR UPDATE"));
            var policy=candidates.stream().filter(p -> !p.getEffectiveFrom().isAfter(
                    boundaries(date,p.getTimezone(),p.getCutoverMinute()).getFirst())).findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            var bounds=boundaries(date,policy.getTimezone(),policy.getCutoverMinute());
            var row=new FnbBusinessDay(); row.setEntityNo(entity); row.setStoreNo(store);
            row.setDayNo(BizKey.next("FDAY")); row.setBusinessDate(date); row.setPolicyNo(policy.getPolicyNo());
            row.setStartsAt(bounds.getFirst()); row.setEndsAt(bounds.getLast()); row.setStatus("OPEN");
            row.setCurrentCloseRefs("{}"); row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setVersion(0L); days.insert(row); return day(row);
        });
    }

    /** Uses local date arithmetic, preserving 23/25 hour days and the store's existing cutover. */
    public static List<LocalDateTime> boundaries(LocalDate date,String timezone,Integer cutover) {
        if(date==null || timezone==null || cutover==null || cutover<0 || cutover>=1440)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        try {
            var zone=ZoneId.of(timezone);
            var start=date.atStartOfDay().plusMinutes(cutover).atZone(zone).toInstant();
            var end=date.plusDays(1).atStartOfDay().plusMinutes(cutover).atZone(zone).toInstant();
            return List.of(LocalDateTime.ofInstant(start,ZoneOffset.UTC),LocalDateTime.ofInstant(end,ZoneOffset.UTC));
        } catch(DateTimeException failure) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }

    @Override public ReportVO report(String entity,String store,String actor,PreviewReq req) {
        scope(entity,store,actor); validate(req);
        return transaction(() -> collect(entity,store,req));
    }

    @Override public CloseVO preview(String entity,String store,String actor,PreviewReq req,String key) {
        scope(entity,store,actor); validate(req);
        return write(entity,store,actor,key,"PREVIEW_CLOSE",json.writeValueAsString(req),CloseVO.class,() -> {
            var day=findDay(entity,store,req.businessDate());
            var report=collect(entity,store,req);
            var history=runs.selectList(Wrappers.<FnbCloseRun>lambdaQuery().eq(FnbCloseRun::getEntityNo,entity)
                    .eq(FnbCloseRun::getDayNo,day.getDayNo()).eq(FnbCloseRun::getCurrency,req.currency())
                    .orderByDesc(FnbCloseRun::getRevision).last("FOR UPDATE"));
            int revision=history.isEmpty()?1:Math.addExact(history.getFirst().getRevision(),1);
            var run=new FnbCloseRun(); run.setEntityNo(entity); run.setCloseNo(BizKey.next("FCLS"));
            run.setDayNo(day.getDayNo()); run.setCurrency(req.currency()); run.setRevision(revision);
            run.setStatus("REVIEW"); run.setSourceWatermark(json.writeValueAsString(report));
            run.setSnapshotHash(snapshotHash(run.getDayNo(),req.currency(),report));
            history.stream().filter(r -> "SIGNED".equals(r.getStatus())).findFirst()
                    .ifPresent(r -> run.setPreviousCloseNo(r.getCloseNo()));
            run.setCreatedBy(actor); run.setUpdatedBy(actor); run.setVersion(0L); runs.insert(run);
            metric(entity,actor,run,"ORDER_BATCH_COUNT",null,BigDecimal.valueOf(report.batchCount()));
            metric(entity,actor,run,"SETTLEMENT_REQUESTED_DUE",report.requestedDueMinor(),null);
            metric(entity,actor,run,"RETURN_REQUEST_COUNT",null,BigDecimal.valueOf(report.returnCount()));
            for(var issue:report.issues()) {
                var row=new FnbCloseException(); row.setEntityNo(entity); row.setCloseNo(run.getCloseNo());
                row.setExceptionNo(BizKey.next("FCEX")); row.setKind(issue.kind());
                row.setSourceRef(issue.sourceRef()); row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setStatus("OPEN"); exceptions.insert(row);
            }
            return close(run);
        });
    }

    @Override public CloseVO get(String entity,String store,String actor,String closeNo) {
        scope(entity,store,actor); key(closeNo,32);
        return transaction(() -> close(findRun(entity,store,closeNo)));
    }

    @Override public CloseVO sign(String entity,String store,String actor,SignReq req,String key) {
        scope(entity,store,actor);
        if(req==null || req.snapshotHash()==null || !req.snapshotHash().matches("[a-f0-9]{64}")) bad();
        key(req.closeNo(),32);
        return write(entity,store,actor,key,"SIGN_CLOSE",json.writeValueAsString(req),CloseVO.class,() -> {
            // Same store-day lock as preview, then the candidate. All close writers use this ordering.
            var run=findRun(entity,store,req.closeNo());
            var day=findDayNo(entity,store,run.getDayNo());
            if(!req.snapshotHash().equals(run.getSnapshotHash()) || !run.getSnapshotHash().equals(
                    snapshotHash(run.getDayNo(),run.getCurrency(),json.readValue(run.getSourceWatermark(),ReportVO.class)))) conflict();
            if("SIGNED".equals(run.getStatus())) return close(run);
            if(!"REVIEW".equals(run.getStatus())) conflict();
            if(day.getEndsAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) conflict();
            if(actor.equals(run.getCreatedBy())) throw BizException.of(ErrorCode.FORBIDDEN);
            var report=json.readValue(run.getSourceWatermark(),ReportVO.class);
            var current=collect(entity,store,new PreviewReq(day.getBusinessDate(),run.getCurrency()));
            if(!report.sourceHash().equals(current.sourceHash()) || !current.issues().isEmpty()) conflict();
            // No carry/resolve escape hatch exists without the authoritative approval contract.
            var issues=exceptions.selectList(Wrappers.<FnbCloseException>lambdaQuery()
                    .eq(FnbCloseException::getEntityNo,entity).eq(FnbCloseException::getCloseNo,run.getCloseNo()));
            if(issues.stream().anyMatch(i -> !"RESOLVED".equals(i.getStatus()))) conflict();
            var pointers=new TreeMap<String,String>();
            json.readTree(day.getCurrentCloseRefs()).properties()
                    .forEach(e -> pointers.put(e.getKey(),e.getValue().asText()));
            if(!Objects.equals(pointers.get(run.getCurrency()),run.getPreviousCloseNo())) conflict();
            run.setStatus("SIGNED"); run.setUpdatedBy(actor); run.setSignedBy(actor); run.setSignedAt(LocalDateTime.now(ZoneOffset.UTC));
            if(runs.updateById(run)!=1) conflict();
            pointers.put(run.getCurrency(),run.getCloseNo()); day.setCurrentCloseRefs(json.writeValueAsString(pointers));
            // Currency signatures alone do not assert store-wide financial completion.
            day.setStatus("CLOSING"); day.setUpdatedBy(actor); if(days.updateById(day)!=1) conflict();
            return close(run);
        });
    }

    @Override public OperationVO adjust(String entity,String store,String actor,AdjustmentReq req,String key) {
        scope(entity,store,actor);
        if(req==null) bad();
        key(req.originalCloseNo(),32); key(req.currentDayNo(),32); key(req.coreReference(),64);
        key(req.reason(),255); key(req.approvalRef(),64);
        return write(entity,store,actor,key,"APPEND_CLOSE_CORRECTION",json.writeValueAsString(req),OperationVO.class,() -> {
            var snapshots=runs.selectList(Wrappers.<FnbCloseRun>lambdaQuery()
                    .eq(FnbCloseRun::getEntityNo,entity).eq(FnbCloseRun::getCloseNo,req.originalCloseNo()));
            if(snapshots.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            // Cross-day corrections acquire both stable day resources in one deterministic order.
            for(var no:List.of(snapshots.getFirst().getDayNo(),req.currentDayNo()).stream().distinct().sorted().toList())
                findDayNo(entity,store,no);
            var original=findRun(entity,store,req.originalCloseNo());
            if(!"SIGNED".equals(original.getStatus())) conflict();
            // Replay by immutable original fact reference, even if a caller changes its idempotency key.
            var found=adjustments.selectList(Wrappers.<FnbCloseAdjustment>lambdaQuery()
                    .eq(FnbCloseAdjustment::getEntityNo,entity)
                    .eq(FnbCloseAdjustment::getOriginalCloseNo,req.originalCloseNo())
                    .eq(FnbCloseAdjustment::getCoreReference,req.coreReference()));
            if(!found.isEmpty()) {
                var r=found.getFirst();
                if(!req.reason().equals(r.getReason()) || !req.approvalRef().equals(r.getApprovalRef())
                        || !req.currentDayNo().equals(r.getCurrentDayNo())) conflict();
                return new OperationVO(r.getAdjustmentNo(),"SUCCEEDED",r.getVersion(),r.getOriginalCloseNo());
            }
            var row=new FnbCloseAdjustment(); row.setEntityNo(entity); row.setAdjustmentNo(BizKey.next("FCAD"));
            row.setOriginalCloseNo(req.originalCloseNo()); row.setCurrentDayNo(req.currentDayNo());
            row.setCoreReference(req.coreReference()); row.setReason(req.reason()); row.setApprovalRef(req.approvalRef());
            row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setVersion(0L); adjustments.insert(row);
            return new OperationVO(row.getAdjustmentNo(),"SUCCEEDED",0,original.getCloseNo());
        });
    }

    private ReportVO collect(String entity,String store,PreviewReq req) {
        var batchRows=batches.selectList(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getEntityNo,entity).eq(FnbOrderBatch::getStoreNo,store)
                .eq(FnbOrderBatch::getBusinessDate,req.businessDate()).orderByAsc(FnbOrderBatch::getBatchNo));
        var lineRows=new ArrayList<FnbOrderLine>();
        var returnRows=new ArrayList<FnbReturn>();
        var settlementRows=new TreeMap<String,FnbSettlement>();
        var quantities=new TreeMap<String,BigDecimal>(); var issues=new ArrayList<Issue>();
        var serviceQuantities=new TreeMap<String,BigDecimal>();
        var serviceRows=new ArrayList<FnbServiceEvent>();
        var bindingRows=new ArrayList<FnbCoreOrderBinding>();
        var itemRows=new ArrayList<FnbSettlementItem>();
        for(var batch:batchRows) {
            var batchLines=lines.selectList(Wrappers.<FnbOrderLine>lambdaQuery().eq(FnbOrderLine::getEntityNo,entity)
                    .eq(FnbOrderLine::getBatchNo,batch.getBatchNo()).orderByAsc(FnbOrderLine::getFoodLineNo));
            lineRows.addAll(batchLines);
            var units=new HashMap<String,String>();
            for(var line:batchLines) units.put(line.getFoodLineNo(),line.getUnit()==null?"UNKNOWN":line.getUnit());
            var ticketRows=tickets.selectList(Wrappers.<FnbKitchenTicket>lambdaQuery()
                    .eq(FnbKitchenTicket::getEntityNo,entity).eq(FnbKitchenTicket::getBatchNo,batch.getBatchNo())
                    .orderByAsc(FnbKitchenTicket::getTicketNo));
            for(var ticket:ticketRows) {
                var taskRows=tasks.selectList(Wrappers.<FnbKitchenTask>lambdaQuery()
                        .eq(FnbKitchenTask::getEntityNo,entity).eq(FnbKitchenTask::getTicketNo,ticket.getTicketNo())
                        .orderByAsc(FnbKitchenTask::getTaskNo));
                for(var task:taskRows) {
                    var eventRows=events.selectList(Wrappers.<FnbServiceEvent>lambdaQuery()
                            .eq(FnbServiceEvent::getEntityNo,entity).eq(FnbServiceEvent::getTaskNo,task.getTaskNo())
                            .orderByAsc(FnbServiceEvent::getEventNo));
                    serviceRows.addAll(eventRows);
                    // Component units are not necessarily their parent line's sale unit.
                    if(task.getComponentNo()==null) for(var event:eventRows) {
                        if(event.getQty()!=null) serviceQuantities.merge(event.getAction()+":"
                                +units.getOrDefault(task.getFoodLineNo(),"UNKNOWN"),event.getQty(),BigDecimal::add);
                    }
                }
            }
            if(Set.of("BOUND","ACCEPTED").contains(batch.getStatus())) for(var line:batchLines)
                quantities.merge(line.getUnit()==null?"UNKNOWN":line.getUnit(),line.getQty(),BigDecimal::add);
            if(!Set.of("BOUND","ACCEPTED","CANCELLED").contains(batch.getStatus()))
                issues.add(new Issue("SYNC",batch.getBatchNo()));
            returnRows.addAll(returns.selectList(Wrappers.<FnbReturn>lambdaQuery().eq(FnbReturn::getEntityNo,entity)
                    .eq(FnbReturn::getStoreNo,store).eq(FnbReturn::getBatchNo,batch.getBatchNo())
                    .orderByAsc(FnbReturn::getReturnNo)));
            var batchBindings=bindings.selectList(Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                    .eq(FnbCoreOrderBinding::getEntityNo,entity).eq(FnbCoreOrderBinding::getBatchNo,batch.getBatchNo())
                    .orderByAsc(FnbCoreOrderBinding::getBindingNo));
            bindingRows.addAll(batchBindings);
            for(var binding:batchBindings) {
                var items=settlementItems.selectList(Wrappers.<FnbSettlementItem>lambdaQuery()
                        .eq(FnbSettlementItem::getEntityNo,entity)
                        .eq(FnbSettlementItem::getPaymentRootNo,binding.getPaymentRootNo())
                        .orderByAsc(FnbSettlementItem::getScopeNo));
                itemRows.addAll(items);
                for(var item:items) {
                    var found=settlements.selectList(Wrappers.<FnbSettlement>lambdaQuery()
                            .eq(FnbSettlement::getEntityNo,entity).eq(FnbSettlement::getStoreNo,store)
                            .eq(FnbSettlement::getCurrency,req.currency())
                            .eq(FnbSettlement::getSettlementNo,item.getSettlementNo()));
                    for(var row:found) settlementRows.put(row.getSettlementNo(),row);
                }
            }
            if(Set.of("BOUND","ACCEPTED").contains(batch.getStatus())) issues.add(new Issue("AR",batch.getBatchNo()));
        }
        long requested=0;
        for(var row:settlementRows.values()) {
            if(row.getDueMinor()!=null) requested=Math.addExact(requested,row.getDueMinor());
            if(!"CANCELLED".equals(row.getStatus())) issues.add(new Issue("PAY_UNKNOWN",row.getSettlementNo()));
        }
        for(var row:returnRows) issues.add(new Issue("REFUND",row.getReturnNo()));
        var corrections=adjustments.selectList(Wrappers.<FnbCloseAdjustment>lambdaQuery()
                .eq(FnbCloseAdjustment::getEntityNo,entity).orderByAsc(FnbCloseAdjustment::getAdjustmentNo))
                .stream().filter(a -> {
                    var run=runs.selectList(Wrappers.<FnbCloseRun>lambdaQuery().eq(FnbCloseRun::getEntityNo,entity)
                            .eq(FnbCloseRun::getCloseNo,a.getOriginalCloseNo()));
                    if(run.isEmpty() || !req.currency().equals(run.getFirst().getCurrency())) return false;
                    var ds=days.selectList(Wrappers.<FnbBusinessDay>lambdaQuery().eq(FnbBusinessDay::getEntityNo,entity)
                            .eq(FnbBusinessDay::getStoreNo,store).eq(FnbBusinessDay::getBusinessDate,req.businessDate()));
                    return !ds.isEmpty() && (ds.getFirst().getDayNo().equals(run.getFirst().getDayNo())
                            || ds.getFirst().getDayNo().equals(a.getCurrentDayNo()));
                }).toList();
        for(var a:corrections) issues.add(new Issue("SYNC",a.getAdjustmentNo()));
        var sorted=issues.stream().distinct().sorted(Comparator.comparing(Issue::kind).thenComparing(Issue::sourceRef)).toList();
        String hash=FoodCommandFingerprint.hash(json.writeValueAsString(batchRows),json.writeValueAsString(lineRows),
                json.writeValueAsString(settlementRows),json.writeValueAsString(returnRows),json.writeValueAsString(corrections),
                json.writeValueAsString(serviceRows),json.writeValueAsString(bindingRows),json.writeValueAsString(itemRows),
                json.writeValueAsString(serviceQuantities));
        return new ReportVO(req.businessDate(),req.currency(),batchRows.size(),quantities,serviceQuantities,
                "ORIGINAL_ORDER_BUSINESS_DATE",settlementRows.size(),requested,
                returnRows.size(),"QUANTITY_ONLY",batchRows.isEmpty()?"EMPTY":"UNKNOWN",
                returnRows.isEmpty()?"EMPTY":"UNKNOWN",sorted,hash);
    }

    private void metric(String entity,String actor,FnbCloseRun run,String code,Long amount,BigDecimal count) {
        var row=new FnbCloseBalance(); row.setEntityNo(entity); row.setBalanceNo(BizKey.next("FCBL"));
        row.setCloseNo(run.getCloseNo()); row.setMetricCode(code); row.setAmountMinor(amount);
        row.setCreatedBy(actor); row.setUpdatedBy(actor);
        row.setCountValue(count); row.setSourceRef("FNB_SOURCE:"+run.getSnapshotHash()); balances.insert(row);
    }
    private FnbBusinessDay findDay(String entity,String store,LocalDate date) {
        var rows=days.selectList(Wrappers.<FnbBusinessDay>lambdaQuery().eq(FnbBusinessDay::getEntityNo,entity)
                .eq(FnbBusinessDay::getStoreNo,store).eq(FnbBusinessDay::getBusinessDate,date).last("FOR UPDATE"));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND); return rows.getFirst();
    }
    private FnbBusinessDay findDayNo(String entity,String store,String no) {
        var rows=days.selectList(Wrappers.<FnbBusinessDay>lambdaQuery().eq(FnbBusinessDay::getEntityNo,entity)
                .eq(FnbBusinessDay::getStoreNo,store).eq(FnbBusinessDay::getDayNo,no).last("FOR UPDATE"));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND); return rows.getFirst();
    }
    private FnbCloseRun findRun(String entity,String store,String no) {
        var rows=runs.selectList(Wrappers.<FnbCloseRun>lambdaQuery().eq(FnbCloseRun::getEntityNo,entity)
                .eq(FnbCloseRun::getCloseNo,no));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        findDayNo(entity,store,rows.getFirst().getDayNo());
        var locked=runs.selectList(Wrappers.<FnbCloseRun>lambdaQuery().eq(FnbCloseRun::getEntityNo,entity)
                .eq(FnbCloseRun::getCloseNo,no).last("FOR UPDATE")); return locked.getFirst();
    }
    private DayVO day(FnbBusinessDay r) {
        return new DayVO(r.getDayNo(),r.getBusinessDate(),r.getPolicyNo(),r.getStartsAt(),r.getEndsAt(),
                r.getStatus(),r.getCurrentCloseRefs(),r.getVersion());
    }
    public static String snapshotHash(String day,String currency,ReportVO report) {
        return PricingSnapshotHash.of(List.of("FOOD_CLOSE_SNAPSHOT_V1",day,currency,report));
    }
    private CloseVO close(FnbCloseRun r) {
        return new CloseVO(r.getCloseNo(),r.getDayNo(),r.getCurrency(),r.getRevision(),r.getStatus(),r.getSnapshotHash(),
                r.getPreviousCloseNo(),r.getSignedBy(),r.getSignedAt(),json.readValue(r.getSourceWatermark(),ReportVO.class),
                r.getVersion());
    }
    private void scope(String entity,String store,String actor) {
        if(blank(entity)||blank(store)||blank(actor)||entity.length()>32||store.length()>32||actor.length()>32)
            throw BizException.of(ErrorCode.FORBIDDEN);
        var card=stores.cards(List.of(store)).get(store);
        if(card==null || !entity.equals(card.entityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private void validate(PreviewReq req) {
        if(req==null || req.currency()==null || !req.currency().matches("[A-Z]{3}")) bad(); date(req.businessDate());
    }
    private void date(LocalDate date) { if(date==null||date.getYear()<1000||date.getYear()>9999) bad(); }
    private void key(String value,int max) { if(blank(value)||value.length()>max) bad(); }
    private boolean blank(String value) { return value==null||value.isBlank(); }
    private void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private <T> T transaction(Supplier<T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Closing requires its own serializable transaction");
        try { return tx.execute(status -> work.get()); }
        catch(org.springframework.dao.ConcurrencyFailureException|org.springframework.dao.DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private <T> T write(String entity,String store,String actor,String key,String action,String payload,
            Class<T> type,Supplier<T> work) {
        key(key,128);
        var scope=FoodCommandFingerprint.scope(action,entity,store);
        return transaction(() -> idem.execute(key,scope,actor,FoodCommandFingerprint.hash(payload),type,work));
    }
}
