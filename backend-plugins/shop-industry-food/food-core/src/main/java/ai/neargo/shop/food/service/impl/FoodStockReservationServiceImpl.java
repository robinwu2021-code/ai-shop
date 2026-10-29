package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.policy.FoodLocalStockBalance;
import ai.neargo.shop.food.policy.FoodLocalStockReservationProgress;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Atomically persists original leaves, exact quantities, movements and permanent command receipts. */
public final class FoodStockReservationServiceImpl implements FoodLocalStockPort {
    private final StockItemMapper items; private final StockBalanceMapper balances;
    private final StockReservationMapper heads; private final StockReservationItemMapper leaves;
    private final StockCommandMapper commands; private final StockMovementMapper movements;
    private final FoodStockSyncPublisher sync;
    private final ObjectMapper json; private final TransactionTemplate write; private final TransactionTemplate read;
    public FoodStockReservationServiceImpl(StockItemMapper items,StockBalanceMapper balances,
            StockReservationMapper heads,StockReservationItemMapper leaves,StockCommandMapper commands,
            StockMovementMapper movements,ObjectMapper json,PlatformTransactionManager manager,FoodStockSyncPublisher sync) {
        this.items=items;this.balances=balances;this.heads=heads;this.leaves=leaves;this.commands=commands;
        this.movements=movements;this.json=json;this.sync=sync;write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager);read.setReadOnly(true);
    }
    @Override public ReservationView getReservation(Scope s,String number) {
        scope(s);text(number,32);noOuter();return read.execute(tx->{
            var h=heads.selectOne(Wrappers.<FnbStockReservation>lambdaQuery().eq(FnbStockReservation::getTenantNo,"MAIN")
                    .eq(FnbStockReservation::getEntityNo,s.entityNo()).eq(FnbStockReservation::getStoreNo,s.storeNo())
                    .eq(FnbStockReservation::getReservationNo,number));
            if(h==null)throw BizException.of(ErrorCode.NOT_FOUND);
            var lines=leaves.selectList(Wrappers.<FnbStockReservationItem>lambdaQuery().eq(FnbStockReservationItem::getTenantNo,"MAIN")
                    .eq(FnbStockReservationItem::getEntityNo,s.entityNo()).eq(FnbStockReservationItem::getStoreNo,s.storeNo())
                    .eq(FnbStockReservationItem::getReservationNo,number).orderByAsc(FnbStockReservationItem::getLeafRef));
            return new ReservationView(number,h.getSourceSystem(),h.getSourceNo(),h.getSourceVersion(),h.getPlanHash(),h.getStatus(),h.getLifecycleVersion(),h.getSourcePlanHash(),h.getProtocolVersion(),h.getExpiresAt(),
                    lines.stream().map(l->new Progress(l.getLeafRef(),l.getItemNo(),l.getBaseUnit(),l.getOriginalQty(),l.getIssuedQty(),l.getReleasedQty(),l.getReturnedQty(),l.getLastIssueMovementNo(),l.getLastIssueCommandNo(),l.getTrackingMode())).toList());
        });
    }
    @Override public ReservationView findReservation(Scope s,String system,String number,long version) {
        scope(s);text(system,32);text(number,64);if(version<0)throw bad();noOuter();
        var ref=read.execute(tx->heads.selectOne(Wrappers.<FnbStockReservation>lambdaQuery()
                .eq(FnbStockReservation::getTenantNo,"MAIN").eq(FnbStockReservation::getEntityNo,s.entityNo())
                .eq(FnbStockReservation::getStoreNo,s.storeNo()).eq(FnbStockReservation::getSourceSystem,system)
                .eq(FnbStockReservation::getSourceNo,number).eq(FnbStockReservation::getSourceVersion,version)));
        return ref==null?null:getReservation(s,ref.getReservationNo());
    }
    @Override public List<Availability> findAvailability(Scope s,List<ItemRef> refs) {
        scope(s);noOuter();bounded(refs,200);
        return read.execute(tx->refs.stream().map(ref->{text(ref.itemNo(),32);text(ref.baseUnit(),16);
            var item=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                    .eq(FnbStockItem::getEntityNo,s.entityNo()).eq(FnbStockItem::getItemNo,ref.itemNo()));
            if(item==null || !ref.baseUnit().equals(item.getBaseUnit()))return new Availability(ref.itemNo(),ref.baseUnit(),"UNCONFIGURED",null);
            var b=balances.selectOne(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                    .eq(FnbStockBalance::getEntityNo,s.entityNo()).eq(FnbStockBalance::getStoreNo,s.storeNo())
                    .eq(FnbStockBalance::getItemNo,ref.itemNo()));
            if(b==null || b.getTrackingMode()==null || b.getStatus()==null)return new Availability(ref.itemNo(),ref.baseUnit(),"UNCONFIGURED",null);
            if(!"ACTIVE".equals(item.getStatus())||!"ACTIVE".equals(b.getStatus()))return new Availability(ref.itemNo(),ref.baseUnit(),"DISABLED",null);
            return new Availability(ref.itemNo(),ref.baseUnit(),b.getTrackingMode(),"TRACKED".equals(b.getTrackingMode())?b.getOnHand().subtract(b.getReservedQty()):null);
        }).toList());
    }
    @Override public Receipt reserve(Scope s,Command c,Plan plan) {
        if(plan==null)throw bad();bounded(plan.leaves(),200);digest(plan.planHash());if(plan.sourcePlanHash()!=null)digest(plan.sourcePlanHash());if(plan.protocolVersion()<1||plan.protocolVersion()>3||plan.protocolVersion()>1&&plan.sourcePlanHash()==null)throw bad();
        if(!plan.planHash().equals(ai.neargo.shop.spi.food.FoodStockFingerprint.plan(json,plan)))throw bad();
        var seen=new HashSet<String>();
        for(var l:plan.leaves()) {
            text(l.leafRef(),64);text(l.sourceComponentRef(),64);text(l.itemNo(),32);text(l.baseUnit(),16);
            positive(l.quantity());text(l.phase(),32);digest(l.definitionHash());text(l.definitionSnapshot(),65536);
            if(!l.definitionHash().equals(ai.neargo.shop.spi.food.FoodStockFingerprint.sha256(l.definitionSnapshot()))||!seen.add(l.leafRef()))throw bad();
        }
        return execute(s,c,"RESERVE",List.of(plan),cmd->{
            if(plan.expiresAt()!=null && !plan.expiresAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC)))throw conflict();
            // Immutable source version cannot reserve twice under different operation keys.
            var old=heads.selectOne(Wrappers.<FnbStockReservation>lambdaQuery().eq(FnbStockReservation::getTenantNo,"MAIN")
                    .eq(FnbStockReservation::getEntityNo,s.entityNo()).eq(FnbStockReservation::getStoreNo,s.storeNo())
                    .eq(FnbStockReservation::getSourceSystem,c.sourceSystem()).eq(FnbStockReservation::getSourceNo,c.sourceNo())
                    .eq(FnbStockReservation::getSourceVersion,c.sourceVersion()));
            if(old!=null)throw conflict();
            var locked=lockItems(s,plan.leaves().stream().map(Leaf::itemNo).toList());
            var h=new FnbStockReservation();audit(h,s);h.setReservationNo(BizKey.next("FSR"));
            h.setSourceSystem(c.sourceSystem());h.setSourceNo(c.sourceNo());h.setSourceVersion(c.sourceVersion());
            h.setOperationKey(c.operationKey());h.setRequestHash(c.requestHash());h.setPlanHash(plan.planHash());h.setSourcePlanHash(plan.sourcePlanHash());h.setProtocolVersion(plan.protocolVersion());
            h.setLifecycleVersion(c.lifecycleVersion());h.setStatus("HELD");h.setExpiresAt(plan.expiresAt());heads.insert(h);
            var refs=new ArrayList<String>();
            for(var l:plan.leaves()) {
                var pair=locked.get(l.itemNo());if(!l.baseUnit().equals(pair.item.getBaseUnit()))throw conflict();
                var leaf=new FnbStockReservationItem();audit(leaf,s);leaf.setReservationNo(h.getReservationNo());
                leaf.setLeafRef(l.leafRef());leaf.setSourceComponentRef(l.sourceComponentRef());leaf.setItemNo(l.itemNo());
                leaf.setBaseUnit(l.baseUnit());leaf.setTrackingMode(pair.balance.getTrackingMode());leaf.setOriginalQty(l.quantity());leaf.setIssuedQty(BigDecimal.ZERO);
                leaf.setReleasedQty(BigDecimal.ZERO);leaf.setReturnedQty(BigDecimal.ZERO);leaf.setPhase(l.phase());
                leaf.setDefinitionHash(l.definitionHash());leaf.setDefinitionSnapshot(l.definitionSnapshot());leaves.insert(leaf);
                if(tracked(pair.balance)) {
                    var before=state(pair.balance);FoodLocalStockBalance after;
                    try{after=before.reserve(l.quantity());}catch(IllegalArgumentException invalid){throw conflict();}
                    save(pair.balance,after);
                    refs.add(movement(s,cmd,h,leaf,"RESERVE",l.quantity(),before,after,null));
                }
            }
            return receipt(cmd,h,refs);
        });
    }
    @Override public Receipt consumeSupplement(Scope s,Command c,Plan plan) {
        if(plan==null||plan.expiresAt()!=null)throw bad();bounded(plan.leaves(),200);
        if(plan.leaves().isEmpty()||plan.sourcePlanHash()==null||plan.protocolVersion()<1||plan.protocolVersion()>3)throw bad();
        digest(plan.sourcePlanHash());if(!plan.planHash().equals(ai.neargo.shop.spi.food.FoodStockFingerprint.plan(json,plan)))throw bad();
        var seen=new HashSet<String>();for(var l:plan.leaves()){text(l.leafRef(),64);text(l.itemNo(),32);text(l.sourceComponentRef(),64);text(l.baseUnit(),16);positive(l.quantity());
            if(!"PRODUCTION".equals(l.phase())||!seen.add(l.leafRef())||l.definitionSnapshot()==null||l.definitionSnapshot().length()>65536
                    ||!ai.neargo.shop.spi.food.FoodStockFingerprint.sha256(l.definitionSnapshot()).equals(l.definitionHash()))throw bad();}
        return execute(s,c,"SUPPLEMENT",List.of(plan),cmd->{
            if(!"CORE_SUPPLEMENT".equals(c.sourceSystem())||c.lifecycleVersion()!=0)throw bad();
            var locked=lockItems(s,plan.leaves().stream().map(Leaf::itemNo).toList());
            var h=new FnbStockReservation();audit(h,s);h.setReservationNo(BizKey.next("FSR"));
            h.setSourceSystem(c.sourceSystem());h.setSourceNo(c.sourceNo());h.setSourceVersion(c.sourceVersion());
            h.setOperationKey(c.operationKey());h.setRequestHash(c.requestHash());h.setPlanHash(plan.planHash());
            h.setSourcePlanHash(plan.sourcePlanHash());h.setProtocolVersion(plan.protocolVersion());
            h.setLifecycleVersion(1L);h.setStatus("COMPLETED");heads.insert(h);
            var refs=new ArrayList<String>();
            for(var l:plan.leaves()){
                var pair=locked.get(l.itemNo());var b=pair.balance;
                if(!l.baseUnit().equals(pair.item.getBaseUnit()))throw conflict();
                var before=state(b);FoodLocalStockBalance after;
                try{after=tracked(b)?before.reserve(l.quantity()).issueReserved(l.quantity()):before;}catch(IllegalArgumentException invalid){throw conflict();}
                var row=new FnbStockReservationItem();audit(row,s);row.setReservationNo(h.getReservationNo());row.setLeafRef(l.leafRef());
                row.setSourceComponentRef(l.sourceComponentRef());row.setItemNo(l.itemNo());row.setBaseUnit(l.baseUnit());row.setTrackingMode(b.getTrackingMode());
                row.setOriginalQty(l.quantity());row.setIssuedQty(l.quantity());row.setReleasedQty(BigDecimal.ZERO);row.setReturnedQty(BigDecimal.ZERO);
                row.setPhase(l.phase());row.setDefinitionSnapshot(l.definitionSnapshot());row.setDefinitionHash(l.definitionHash());
                if(tracked(b))save(b,after);var movement=movement(s,cmd,h,row,tracked(b)?"SUPPLEMENT":"NO_STOCK_SUPPLEMENT",l.quantity(),before,after,null);
                row.setLastIssueMovementNo(movement);row.setLastIssueCommandNo(cmd.getCommandNo());leaves.insert(row);refs.add(movement);
            }
            return receipt(cmd,h,refs);
        });
    }
    @Override public Receipt withdraw(Scope s,Command c,Plan plan) {return withdraw(s,c,plan,false);}
    @Override public Receipt expire(Scope s,Command c,Plan plan) {return withdraw(s,c,plan,true);}
    private Receipt withdraw(Scope s,Command c,Plan plan,boolean expiry) {
        if(plan==null)throw bad();digest(plan.planHash());if(plan.sourcePlanHash()!=null)digest(plan.sourcePlanHash());if(plan.protocolVersion()<1||plan.protocolVersion()>3||plan.protocolVersion()>1&&plan.sourcePlanHash()==null)throw bad();
        if(!plan.planHash().equals(ai.neargo.shop.spi.food.FoodStockFingerprint.plan(json,plan)))throw bad();
        if(expiry&&(plan.expiresAt()==null||plan.expiresAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC))))throw bad();
        return execute(s,c,expiry?"EXPIRE":"WITHDRAW",List.of(plan),cmd->{
            var h=heads.selectOne(Wrappers.<FnbStockReservation>lambdaQuery().eq(FnbStockReservation::getTenantNo,"MAIN")
                    .eq(FnbStockReservation::getEntityNo,s.entityNo()).eq(FnbStockReservation::getStoreNo,s.storeNo())
                    .eq(FnbStockReservation::getSourceSystem,c.sourceSystem()).eq(FnbStockReservation::getSourceNo,c.sourceNo())
                    .eq(FnbStockReservation::getSourceVersion,c.sourceVersion()).last("FOR UPDATE"));
            if(h==null){if(expiry)throw conflict();h=new FnbStockReservation();audit(h,s);h.setReservationNo(BizKey.next("FSR"));
                h.setSourceSystem(c.sourceSystem());h.setSourceNo(c.sourceNo());h.setSourceVersion(c.sourceVersion());
                h.setOperationKey(c.operationKey());h.setRequestHash(c.requestHash());h.setPlanHash(plan.planHash());h.setSourcePlanHash(plan.sourcePlanHash());h.setProtocolVersion(plan.protocolVersion());
                h.setLifecycleVersion(c.lifecycleVersion());h.setStatus("WITHDRAWN");heads.insert(h);
                return receipt(cmd,h,List.of());}
            if(!h.getPlanHash().equals(plan.planHash()))throw conflict();
            if(Set.of("WITHDRAWN","RELEASED","EXPIRED").contains(h.getStatus()))return receipt(cmd,h,List.of());
            if(expiry&&!"HELD".equals(h.getStatus()))throw conflict();
            if(h.getLifecycleVersion()<c.lifecycleVersion())throw conflict();
            var original=rows(s,h.getReservationNo());
            if(original.stream().anyMatch(l->l.getIssuedQty().signum()>0))throw conflict();
            var locked=lockItems(s,original.stream().map(FnbStockReservationItem::getItemNo).toList());
            var refs=new ArrayList<String>();
            for(var l:original){var delta=l.getOriginalQty().subtract(l.getReleasedQty());if(delta.signum()==0)continue;
                var b=locked.get(l.getItemNo()).balance;var before=state(b);
                if(frozenTracked(l,b)){var after=before.release(delta);save(b,after);refs.add(movement(s,cmd,h,l,"RELEASE",delta,before,after,null));}
                l.setReleasedQty(l.getOriginalQty());if(leaves.updateById(l)!=1)throw conflict();}
            h.setStatus(expiry?"EXPIRED":"WITHDRAWN");advance(h);return receipt(cmd,h,refs);
        });
    }
    @Override public Receipt confirm(Scope s,Command c,String reservation) {
        text(reservation,32);
        return execute(s,c,"CONFIRM",List.of(reservation),cmd->{var h=head(s,c,reservation);
            if(Set.of("CONFIRMED","PARTIAL","COMPLETED").contains(h.getStatus()))return receipt(cmd,h,List.of());
            if(!"HELD".equals(h.getStatus())||h.getExpiresAt()!=null&&!h.getExpiresAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC)))throw conflict();
            h.setStatus("CONFIRMED");advance(h);return receipt(cmd,h,List.of());});
    }
    @Override public Receipt issue(Scope s,Command c,String reservation,List<Target> targets) {
        return progress(s,c,reservation,targets,true,false);
    }
    @Override public Receipt release(Scope s,Command c,String reservation,List<Target> targets) {
        return progress(s,c,reservation,targets,false,false);
    }
    @Override public Receipt issueDelta(Scope s,Command c,String reservation,List<Target> targets) {
        return progress(s,c,reservation,targets,true,true);
    }
    private Receipt progress(Scope s,Command c,String reservation,List<Target> targets,boolean issue,boolean incremental) {
        text(reservation,32);
        bounded(targets,200);var seen=new HashSet<String>();for(var t:targets){text(t.leafRef(),64);quantity(t.targetQty());if(!seen.add(t.leafRef()))throw bad();}
        return execute(s,c,incremental?"ISSUE_DELTA":issue?"ISSUE":"RELEASE",List.of(reservation,targets),cmd->{
            var h=head(s,c,reservation);
            if(issue&&!Set.of("CONFIRMED","PARTIAL","COMPLETED").contains(h.getStatus()))throw conflict();
            if(!issue&&!Set.of("HELD","CONFIRMED","PARTIAL").contains(h.getStatus()))throw conflict();
            var rows=rows(s,reservation);var byLeaf=new HashMap<String,FnbStockReservationItem>();rows.forEach(l->byLeaf.put(l.getLeafRef(),l));
            var locked=lockItems(s,rows.stream().map(FnbStockReservationItem::getItemNo).toList());var refs=new ArrayList<String>();
            for(var t:targets){var leaf=byLeaf.get(t.leafRef());if(leaf==null)throw bad();var b=locked.get(leaf.getItemNo()).balance;
                var p=new FoodLocalStockReservationProgress(leaf.getOriginalQty(),leaf.getIssuedQty(),leaf.getReleasedQty());
                var before=state(b);BigDecimal prior=issue?leaf.getIssuedQty():leaf.getReleasedQty();
                BigDecimal target=incremental?prior.add(t.targetQty()):t.targetQty();
                if(incremental&&t.targetQty().signum()<=0)throw bad();
                BigDecimal delta=target.subtract(prior);
                if(delta.signum()<0||target.add(issue?leaf.getReleasedQty():leaf.getIssuedQty()).compareTo(leaf.getOriginalQty())>0)throw conflict();
                if(delta.signum()==0)continue;
                boolean physical=frozenTracked(leaf,b);
                if(physical) {
                    FoodLocalStockBalance after;
                    try{after=(issue?p.issueTo(before,target):p.releaseTo(before,target)).balance();}
                    catch(IllegalArgumentException invalid){throw conflict();}
                    save(b,after);var movement=movement(s,cmd,h,leaf,issue?"ISSUE":"RELEASE",delta,before,after,null);refs.add(movement);if(issue){leaf.setLastIssueMovementNo(movement);leaf.setLastIssueCommandNo(cmd.getCommandNo());}
                }
                if(!physical) {
                    var audit=movement(s,cmd,h,leaf,issue?"NO_STOCK_ISSUE":"NO_STOCK_RELEASE",delta,before,before,null);
                    refs.add(audit);if(issue){leaf.setLastIssueMovementNo(audit);leaf.setLastIssueCommandNo(cmd.getCommandNo());}
                }
                if(issue)leaf.setIssuedQty(target);else leaf.setReleasedQty(target);
                if(leaves.updateById(leaf)!=1)throw conflict();
            }
            boolean complete=rows.stream().allMatch(l->l.getIssuedQty().add(l.getReleasedQty()).compareTo(l.getOriginalQty())==0);
            h.setStatus(complete?(rows.stream().allMatch(l->l.getIssuedQty().signum()==0)?"RELEASED":"COMPLETED"):"HELD".equals(h.getStatus())?"HELD":"PARTIAL");
            advance(h);return receipt(cmd,h,refs);
        });
    }
    @Override public Receipt returnIssued(Scope s,Command c,String reservation,List<ReturnTarget> targets) {
        text(reservation,32);
        bounded(targets,200);var seen=new HashSet<String>();for(var t:targets){text(t.leafRef(),64);text(t.originalMovementNo(),32);
            text(t.returnRef(),64);quantity(t.targetQty());
            if(t.requireUniformDisposition()){text(t.reasonCode(),32);text(t.actorNo(),32);if(t.reasonDetail()!=null)text(t.reasonDetail(),256);}if(!Set.of("RESALE","LOSS").contains(t.disposition()==null?"":t.disposition())||!seen.add(t.originalMovementNo()))throw bad();}
        return execute(s,c,"RETURN",List.of(reservation,targets),cmd->{var h=head(s,c,reservation);
            var rows=rows(s,reservation);var map=new HashMap<String,FnbStockReservationItem>();rows.forEach(l->map.put(l.getLeafRef(),l));
            var locked=lockItems(s,rows.stream().map(FnbStockReservationItem::getItemNo).toList());var refs=new ArrayList<String>();
            for(var t:targets){var l=map.get(t.leafRef());if(l==null)throw bad();var b=locked.get(l.getItemNo()).balance;
                var original=movements.selectOne(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN")
                        .eq(FnbStockMovement::getEntityNo,s.entityNo()).eq(FnbStockMovement::getStoreNo,s.storeNo())
                        .eq(FnbStockMovement::getMovementNo,t.originalMovementNo()).last("FOR UPDATE"));
                if(original==null||!Set.of("ISSUE","SUPPLEMENT","NO_STOCK_ISSUE","NO_STOCK_SUPPLEMENT").contains(original.getAction())||!reservation.equals(original.getReservationNo())
                        ||!l.getLeafRef().equals(original.getLineRef())||!l.getItemNo().equals(original.getItemNo()))throw conflict();
                boolean physical=frozenTracked(l,b);
                if(physical==original.getAction().startsWith("NO_STOCK_"))throw conflict();
                var sum=movements.selectObjs(new QueryWrapper<FnbStockMovement>().select("COALESCE(SUM(quantity),0)")
                        .eq("tenant_no","MAIN").eq("entity_no",s.entityNo()).eq("store_no",s.storeNo())
                        .eq("original_movement_no",original.getMovementNo()));
                if(t.requireUniformDisposition()&&movements.selectCount(Wrappers.<FnbStockMovement>lambdaQuery()
                        .eq(FnbStockMovement::getTenantNo,"MAIN").eq(FnbStockMovement::getEntityNo,s.entityNo())
                        .eq(FnbStockMovement::getStoreNo,s.storeNo()).eq(FnbStockMovement::getOriginalMovementNo,original.getMovementNo())
                        .in(FnbStockMovement::getAction,"RESALE".equals(t.disposition())?List.of("LOSS","NO_STOCK_LOSS"):List.of("RETURN","NO_STOCK_RETURN")))>0)throw conflict();
                var prior=new BigDecimal(sum.getFirst().toString());var total=t.incremental()?prior.add(t.targetQty()):t.targetQty();var delta=total.subtract(prior);
                if(t.incremental()&&delta.signum()<=0)throw bad();
                if(delta.signum()<0||total.compareTo(original.getQuantity())>0)throw conflict();if(delta.signum()==0)continue;
                var before=state(b);var after=physical&&"RESALE".equals(t.disposition())?before.adjust(delta):before;
                if("RESALE".equals(t.disposition())){l.setReturnedQty(l.getReturnedQty().add(delta));if(l.getReturnedQty().compareTo(l.getIssuedQty())>0)throw conflict();if(leaves.updateById(l)!=1)throw conflict();if(physical)save(b,after);}
                var no=movement(s,cmd,h,l,(physical?"":"NO_STOCK_")+("RESALE".equals(t.disposition())?"RETURN":"LOSS"),delta,before,after,original.getMovementNo(),t.reasonCode(),t.reasonDetail(),t.actorNo());refs.add(no);
            }
            advance(h);return receipt(cmd,h,refs);
        });
    }
    @Override public Lookup findCommand(Scope s,String action,String key,String hash) {
        scope(s);text(action,32);text(key,128);digest(hash);noOuter();return read.execute(tx->{var row=command(s,action,key);
            if(row==null)return new Lookup("NOT_FOUND",null);if(!hash.equals(row.getRequestHash()))return new Lookup("CONFLICT",null);
            if(!"COMPLETED".equals(row.getStatus()))return new Lookup("IN_PROGRESS",null);
            return new Lookup("COMPLETED",json.readValue(row.getResultJson(),Receipt.class));});
    }
    private Receipt execute(Scope s,Command c,String action,Object payload,Function<FnbStockCommand,Receipt> work) {
        scope(s);if(c==null)throw bad();text(c.operationKey(),128);digest(c.requestHash());text(c.sourceSystem(),32);text(c.sourceNo(),64);
        if(c.sourceVersion()<0||c.lifecycleVersion()<0||c.lifecycleVersion()==Long.MAX_VALUE)throw bad();noOuter();
        // Store the computed payload fingerprint, not a caller-supplied claim about its contents.
        String fingerprint=ai.neargo.shop.spi.food.FoodStockFingerprint.request(json,action,c,payload);
        if(!fingerprint.equals(c.requestHash()))throw bad();
        try{return write.execute(tx->{var old=command(s,action,c.operationKey());
            if(old!=null){if(!fingerprint.equals(old.getRequestHash())||!"COMPLETED".equals(old.getStatus()))throw conflict();return json.readValue(old.getResultJson(),Receipt.class);}
            var cmd=new FnbStockCommand();audit(cmd,s);cmd.setCommandNo(BizKey.next("FSC"));cmd.setAction(action);
            cmd.setOperationKey(c.operationKey());cmd.setRequestHash(fingerprint);cmd.setRequestSnapshot(json.writeValueAsString(new Object[]{c,payload}));cmd.setStatus("PROCESSING");commands.insert(cmd);
            var result=work.apply(cmd);cmd.setStatus("COMPLETED");cmd.setResultJson(json.writeValueAsString(result));if(commands.updateById(cmd)!=1)throw conflict();return result;
        });}catch(DuplicateKeyException|ConcurrencyFailureException failure){throw conflict();}
    }
    private FnbStockCommand command(Scope s,String action,String key){return commands.selectOne(Wrappers.<FnbStockCommand>lambdaQuery().eq(FnbStockCommand::getTenantNo,"MAIN").eq(FnbStockCommand::getEntityNo,s.entityNo()).eq(FnbStockCommand::getStoreNo,s.storeNo()).eq(FnbStockCommand::getAction,action).eq(FnbStockCommand::getOperationKey,key));}
    private FnbStockReservation head(Scope s,Command c,String number){text(number,32);var h=heads.selectOne(Wrappers.<FnbStockReservation>lambdaQuery().eq(FnbStockReservation::getTenantNo,"MAIN").eq(FnbStockReservation::getEntityNo,s.entityNo()).eq(FnbStockReservation::getStoreNo,s.storeNo()).eq(FnbStockReservation::getReservationNo,number).last("FOR UPDATE"));
        if(h==null)throw BizException.of(ErrorCode.NOT_FOUND);
        if(!h.getSourceSystem().equals(c.sourceSystem())||!h.getSourceNo().equals(c.sourceNo())||h.getSourceVersion()!=c.sourceVersion()||h.getLifecycleVersion()<c.lifecycleVersion())throw conflict();return h;}
    private List<FnbStockReservationItem> rows(Scope s,String number){return leaves.selectList(Wrappers.<FnbStockReservationItem>lambdaQuery().eq(FnbStockReservationItem::getTenantNo,"MAIN").eq(FnbStockReservationItem::getEntityNo,s.entityNo()).eq(FnbStockReservationItem::getStoreNo,s.storeNo()).eq(FnbStockReservationItem::getReservationNo,number).orderByAsc(FnbStockReservationItem::getLeafRef).last("FOR UPDATE"));}
    private record Pair(FnbStockItem item,FnbStockBalance balance){}
    private Map<String,Pair> lockItems(Scope s,List<String> numbers){var result=new LinkedHashMap<String,Pair>();
        for(String number:new TreeSet<>(numbers)){var i=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN").eq(FnbStockItem::getEntityNo,s.entityNo()).eq(FnbStockItem::getItemNo,number).last("FOR UPDATE"));
            var b=balances.lockBalance(s.entityNo(),s.storeNo(),number);
            if(i==null||b==null||!"ACTIVE".equals(i.getStatus())||!"ACTIVE".equals(b.getStatus())||!Set.of("TRACKED","UNTRACKED").contains(b.getTrackingMode()==null?"":b.getTrackingMode()))throw conflict();result.put(number,new Pair(i,b));}return result;}
    private static boolean frozenTracked(FnbStockReservationItem leaf,FnbStockBalance balance) {
        if(leaf.getTrackingMode()==null||!leaf.getTrackingMode().equals(balance.getTrackingMode()))throw conflict();
        return "TRACKED".equals(leaf.getTrackingMode());
    }
    private Receipt receipt(FnbStockCommand cmd,FnbStockReservation h,List<String> refs){
        for(String item:new TreeSet<>(rows(new Scope(h.getEntityNo(),h.getStoreNo()),h.getReservationNo()).stream().map(FnbStockReservationItem::getItemNo).toList()))
            sync.capture(h.getEntityNo(),h.getStoreNo(),item);
        var scope=new Scope(h.getEntityNo(),h.getStoreNo());return new Receipt(cmd.getCommandNo(),h.getReservationNo(),"FOOD_LOCAL",h.getPlanHash(),h.getStatus(),h.getLifecycleVersion(),rows(scope,h.getReservationNo()).stream().map(l->new Progress(l.getLeafRef(),l.getItemNo(),l.getBaseUnit(),l.getOriginalQty(),l.getIssuedQty(),l.getReleasedQty(),l.getReturnedQty(),l.getLastIssueMovementNo(),l.getLastIssueCommandNo(),l.getTrackingMode())).toList(),refs);}
    private void advance(FnbStockReservation h){if(h.getLifecycleVersion()==Long.MAX_VALUE)throw conflict();h.setLifecycleVersion(h.getLifecycleVersion()+1);if(heads.updateById(h)!=1)throw conflict();}
    private void save(FnbStockBalance b,FoodLocalStockBalance state){b.setOnHand(state.onHand());b.setReservedQty(state.reserved());if(balances.updateById(b)!=1)throw conflict();}
    private String movement(Scope s,FnbStockCommand cmd,FnbStockReservation h,FnbStockReservationItem leaf,String action,BigDecimal qty,FoodLocalStockBalance before,FoodLocalStockBalance after,String original){
        return movement(s,cmd,h,leaf,action,qty,before,after,original,null,null,null);
    }
    private String movement(Scope s,FnbStockCommand cmd,FnbStockReservation h,FnbStockReservationItem leaf,String action,BigDecimal qty,FoodLocalStockBalance before,FoodLocalStockBalance after,String original,String reasonCode,String reasonDetail,String actor){var m=new FnbStockMovement();audit(m,s);m.setMovementNo(BizKey.next("FSM"));m.setCommandNo(cmd.getCommandNo());m.setLineRef(leaf.getLeafRef());m.setItemNo(leaf.getItemNo());m.setReservationNo(h.getReservationNo());m.setAction(action);m.setQuantity(qty);m.setBaseUnit(leaf.getBaseUnit());m.setOnHandBefore(before.onHand());m.setOnHandAfter(after.onHand());m.setReservedBefore(before.reserved());m.setReservedAfter(after.reserved());m.setOriginalMovementNo(original);m.setReasonCode(reasonCode);m.setReasonDetail(reasonDetail);if(actor!=null){m.setCreatedBy(actor);m.setUpdatedBy(actor);}movements.insert(m);return m.getMovementNo();}
    private static FoodLocalStockBalance state(FnbStockBalance b){return new FoodLocalStockBalance(b.getOnHand(),b.getReservedQty());}
    private static boolean tracked(FnbStockBalance b){return "TRACKED".equals(b.getTrackingMode());}
    private static void audit(BaseEntity row,Scope s){row.setTenantNo("MAIN");row.setVersion(0L);row.setDeleted(0);row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());row.setCreatedBy("SYSTEM");row.setUpdatedBy("SYSTEM");if(row instanceof FnbStockReservation h){h.setEntityNo(s.entityNo());h.setStoreNo(s.storeNo());}if(row instanceof FnbStockReservationItem l){l.setEntityNo(s.entityNo());l.setStoreNo(s.storeNo());}if(row instanceof FnbStockCommand c){c.setEntityNo(s.entityNo());c.setStoreNo(s.storeNo());}if(row instanceof FnbStockMovement m){m.setEntityNo(s.entityNo());m.setStoreNo(s.storeNo());}}
    private static void quantity(BigDecimal q){try{new FoodLocalStockBalance(q,BigDecimal.ZERO);}catch(IllegalArgumentException invalid){throw bad();}}
    private static void positive(BigDecimal q){quantity(q);if(q.signum()==0)throw bad();}
    private static void bounded(List<?> v,int max){if(v==null||v.size()>max||v.stream().anyMatch(Objects::isNull))throw bad();}
    private static void digest(String s){if(s==null||!s.matches("[a-f0-9]{64}"))throw bad();}
    private static void scope(Scope s){if(s==null)throw bad();text(s.entityNo(),32);text(s.storeNo(),32);}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max||!s.equals(s.trim()))throw bad();}
    private static void noOuter(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock commands require their own transaction boundary");}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
