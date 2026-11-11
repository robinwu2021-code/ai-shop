package ai.neargo.shop.food.port;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.policy.FoodLocalStockBalance;
import ai.neargo.shop.food.service.support.FoodStockSyncPublisher;
import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import java.util.function.Function;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Permanent original-line accounting; source HTTP calls finish before entering this transaction. */
public final class FoodStockReplenishmentPortImpl implements FoodStockReplenishmentPort {
    private final StockItemMapper items;
    private final StockBalanceMapper balances;
    private final StockCommandMapper commands;
    private final StockMovementMapper movements;
    private final StockReplenishmentMapper replenishments;
    private final StockReplenishmentReturnMapper returns;
    private final StockReplenishmentConnectionMapper connections;
    private final StockReplenishmentManualMapper manuals;
    private final ObjectMapper json;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final FoodStockSyncPublisher sync;
    public FoodStockReplenishmentPortImpl(StockItemMapper items,StockBalanceMapper balances,
            StockCommandMapper commands,StockMovementMapper movements,StockReplenishmentMapper replenishments,
            StockReplenishmentReturnMapper returns,StockReplenishmentConnectionMapper connections,StockReplenishmentManualMapper manuals,ObjectMapper json,PlatformTransactionManager manager,FoodStockSyncPublisher sync){
        this.items=items;this.balances=balances;this.commands=commands;this.movements=movements;
        this.replenishments=replenishments;this.returns=returns;this.connections=connections;this.manuals=manuals;this.json=json;this.sync=sync;
        write=new TransactionTemplate(manager);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager);read.setReadOnly(true);
    }
    @JsonFormat(shape=JsonFormat.Shape.ARRAY)
    private record RegistrationSnapshot(String actor,String action,RegistrationPayload payload) {}
    @JsonFormat(shape=JsonFormat.Shape.ARRAY)
    private record RegistrationPayload(RegistrationIdentity source,Mapping mapping) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record RegistrationIdentity(String documentNo,int lineNo) {}
    @JsonFormat(shape=JsonFormat.Shape.ARRAY)
    private record ManualRegistrationSnapshot(String actor,String action,ManualRegistrationPayload payload) {}
    @JsonFormat(shape=JsonFormat.Shape.ARRAY)
    private record ManualRegistrationPayload(RegistrationIdentity source,Mapping mapping,String movement) {}
    @Override public View recoverManualRegistration(Scope scope,String actor,String key,String doc,int line,Mapping mapping,String movement){
        noOuter();return read.execute(tx->{
            var c=lookup(scope,"REPLENISH_MANUAL_REGISTER",key);if(c==null)return null;
            if(!"COMPLETED".equals(c.getStatus()))throw conflict();
            var frozen=json.readValue(c.getRequestSnapshot(),ManualRegistrationSnapshot.class);
            if(!actor.equals(frozen.actor())||!"REPLENISH_MANUAL_REGISTER".equals(frozen.action())
                    ||!doc.equals(frozen.payload().source().documentNo())||line!=frozen.payload().source().lineNo()
                    ||!mapping.equals(frozen.payload().mapping())||!movement.equals(frozen.payload().movement()))throw conflict();
            return result(c,View.class);
        });
    }
    @Override public View findSource(Scope scope,String system,String owner,String document,int line) {
        noOuter();return read.execute(tx->{
            var h=replenishments.findSourceIdentity(system,owner,document,line);
            if(h==null)return null;
            if(!scope.entityNo().equals(h.getEntityNo())||!scope.storeNo().equals(h.getStoreNo()))throw conflict();
            return view(h);
        });
    }
    @Override public View recoverRegistration(Scope scope,String actor,String key,String doc,int line,Mapping mapping){
        noOuter();return read.execute(tx->{var c=lookup(scope,"REPLENISH_REGISTER",key);if(c==null)return null;if(!"COMPLETED".equals(c.getStatus()))throw conflict();
            var frozen=json.readValue(c.getRequestSnapshot(),RegistrationSnapshot.class);
            if(!actor.equals(frozen.actor())||!"REPLENISH_REGISTER".equals(frozen.action())
                    ||!doc.equals(frozen.payload().source().documentNo())||line!=frozen.payload().source().lineNo()
                    ||!mapping.equals(frozen.payload().mapping()))throw conflict();
            return result(c,View.class);
        });
    }
    private static final Set<String> ACTIONS=Set.of("REPLENISH_CONFIG","REPLENISH_REGISTER","REPLENISH_RECEIVE","REPLENISH_CLOSE","REPLENISH_RETURN","REPLENISH_RETURN_ACK","REPLENISH_MANUAL","REPLENISH_MANUAL_REGISTER");
    private CommandState state(FnbStockCommand command){
        if(command==null)return new CommandState("NOT_COMMITTED",null,null);
        if("CANCELLED".equals(command.getStatus()))return new CommandState("CANCELLED",null,null);
        if(!"COMPLETED".equals(command.getStatus()))return new CommandState("UNKNOWN",null,null);
        return "REPLENISH_CONFIG".equals(command.getAction())?new CommandState("COMPLETED",null,result(command,Connection.class))
                :new CommandState("COMPLETED",result(command,View.class),null);
    }
    @Override public CommandState commandState(Scope scope,String action,String key){
        noOuter();if(!ACTIONS.contains(action))throw conflict();return read.execute(tx->state(lookup(scope,action,key)));
    }
    @Override public CommandState cancelCommand(Scope scope,String actor,String action,String key,String reason){
        noOuter();if(!ACTIONS.contains(action))throw conflict();
        try{return write.execute(tx->{
            var old=lookup(scope,action,key);
            if(old!=null){if(!actor.equals(old.getCreatedBy()))throw BizException.of(ErrorCode.FORBIDDEN);return state(old);}
            var tombstone=new FnbStockCommand();audit(tombstone,scope,actor);tombstone.setCommandNo(BizKey.next("FSC"));
            tombstone.setAction(action);tombstone.setOperationKey(key);tombstone.setStatus("CANCELLED");
            String snapshot=json.writeValueAsString(new Object[]{actor,"CANCEL_PENDING",action,key,reason});
            tombstone.setRequestSnapshot(snapshot);tombstone.setRequestHash(FoodStockFingerprint.sha256(snapshot));commands.insert(tombstone);
            return state(tombstone);
        });}catch(DuplicateKeyException race){
            // An original commit or another cancellation won the same permanent key; never report a false cancellation.
            return read.execute(tx->{var winner=lookup(scope,action,key);if(winner==null)throw conflict();
                if(!actor.equals(winner.getCreatedBy()))throw BizException.of(ErrorCode.FORBIDDEN);return state(winner);});
        }
    }
    @Override public Connection connection(Scope scope){noOuter();return read.execute(tx->connectionView(connectionRow(scope,false)));}
    @Override public Connection findConfigurationCommand(Scope scope,String key){
        noOuter();return read.execute(tx->{var c=lookup(scope,"REPLENISH_CONFIG",key);return c==null?null:result(c,Connection.class);});
    }
    @Override public Connection recoverConfiguration(Scope scope,String actor,String key,ConnectionChange change){
        noOuter();return read.execute(tx->{var c=lookup(scope,"REPLENISH_CONFIG",key);if(c==null)return null;
            if(!c.getRequestHash().equals(fingerprint(actor,"REPLENISH_CONFIG",change)))throw conflict();return result(c,Connection.class);
        });
    }
    @Override public Connection configure(Scope scope,String actor,String key,ConnectionChange change,Checkpoint source){
        return command(scope,actor,"REPLENISH_CONFIG",key,change,Connection.class,cmd->{
            var c=connectionRow(scope,true);
            if(c==null){if(change.expectedVersion()!=0)throw conflict();c=new FnbStockReplenishmentConnection();audit(c,scope,actor);
                c.setStatus("OFF");c.setGeneration(0L);c.setPostingWatermark(0L);connections.insert(c);}
            if(c.getVersion()!=change.expectedVersion())throw conflict();
            boolean enabled="ACTIVE".equals(c.getStatus());
            if(enabled!=change.enabled()){
            if(change.enabled()){
                if(source==null||source.sourceSystem()==null||source.sourceOwner()==null||source.postingSequence()<0
                        ||c.getGeneration()==Long.MAX_VALUE)throw conflict();
                if(c.getSourceSystem()!=null&&!sameSource(c,source.sourceSystem(),source.sourceOwner()))throw conflict();
                c.setSourceSystem(source.sourceSystem());c.setSourceOwner(source.sourceOwner());c.setPostingWatermark(source.postingSequence());
                c.setGeneration(c.getGeneration()+1);c.setStatus("ACTIVE");
            }else c.setStatus("OFF");
            }
            if(c.getVersion()==Long.MAX_VALUE)throw conflict();c.setUpdatedBy(actor);if(connections.updateById(c)!=1)throw conflict();return connectionView(c);
        });
    }
    private String fingerprint(String actor,String action,Object payload){return FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{actor,action,payload}));}
    private FnbStockReplenishmentConnection connectionRow(Scope scope,boolean lock){
        return connections.selectOne(Wrappers.<FnbStockReplenishmentConnection>lambdaQuery()
                .eq(FnbStockReplenishmentConnection::getTenantNo,"MAIN").eq(FnbStockReplenishmentConnection::getEntityNo,scope.entityNo())
                .eq(FnbStockReplenishmentConnection::getStoreNo,scope.storeNo()).last(lock?"FOR UPDATE":""));
    }
    private static boolean sameSource(FnbStockReplenishmentConnection c,String system,String owner){return system.equals(c.getSourceSystem())&&owner.equals(c.getSourceOwner());}
    private static Connection connectionView(FnbStockReplenishmentConnection c){return c==null?new Connection(false,0,0,null,null,0)
            :new Connection("ACTIVE".equals(c.getStatus()),c.getGeneration(),c.getVersion(),c.getSourceSystem(),c.getSourceOwner(),c.getPostingWatermark());}
    @Override public View register(Scope s,String actor,String key,Outbound source,Mapping mapping){
        positive(source.quantity());positive(mapping.factor());
        if(!s.storeNo().equals(source.targetStoreNo())||source.lineNo()<1)throw conflict();
        return command(s,actor,"REPLENISH_REGISTER",key,new Object[]{source,mapping},View.class,cmd->{
            var old=replenishments.selectOne(Wrappers.<FnbStockReplenishment>lambdaQuery()
                    .eq(FnbStockReplenishment::getTenantNo,"MAIN").eq(FnbStockReplenishment::getSourceSystem,source.sourceSystem())
                    .eq(FnbStockReplenishment::getSourceOwner,source.sourceOwner()).eq(FnbStockReplenishment::getSourceDocument,source.documentNo())
                    .eq(FnbStockReplenishment::getSourceLine,source.lineNo()));
            if(old!=null){
                if(!s.entityNo().equals(old.getEntityNo())||!s.storeNo().equals(old.getStoreNo())
                        ||!mapping.itemNo().equals(old.getItemNo())||!mapping.baseUnit().equals(old.getBaseUnit())
                        ||mapping.factor().compareTo(old.getFactor())!=0||!source.itemNo().equals(old.getSourceItem())
                        ||!source.baseUnit().equals(old.getSourceUnit())||source.quantity().compareTo(old.getSourceQuantity())!=0)throw conflict();
                return view(old);
            }
            var connection=connectionRow(s,true);
            if(connection==null||!"ACTIVE".equals(connection.getStatus())||!sameSource(connection,source.sourceSystem(),source.sourceOwner())
                    ||source.postingSequence()<=connection.getPostingWatermark())throw conflict();
            var h=new FnbStockReplenishment();audit(h,s,actor);h.setReplenishmentNo(BizKey.next("FRP"));
            h.setAdmissionGeneration(connection.getGeneration());h.setAdmissionMode("CONNECTED");h.setSourcePostingSequence(source.postingSequence());
            h.setSourceSystem(source.sourceSystem());h.setSourceOwner(source.sourceOwner());h.setSourceDocument(source.documentNo());
            h.setSourceLine(source.lineNo());h.setSourceItem(source.itemNo());h.setSourceUnit(source.baseUnit());h.setSourceQuantity(source.quantity());
            h.setItemNo(mapping.itemNo());h.setBaseUnit(mapping.baseUnit());h.setFactor(mapping.factor());
            h.setExpectedQuantity(positive(source.quantity().multiply(mapping.factor())));
            h.setReceivedQuantity(BigDecimal.ZERO);h.setDifferenceQuantity(BigDecimal.ZERO);h.setReturnedQuantity(BigDecimal.ZERO);
            h.setReturnAcceptedQuantity(BigDecimal.ZERO);h.setStatus("PENDING");lockBalance(s,h);
            replenishments.insert(h);return view(h);
        });
    }
    @Override public View receive(Scope s,String actor,String key,String no,BigDecimal target){
        positive(target);return command(s,actor,"REPLENISH_RECEIVE",key,new Object[]{no,canonical(target)},View.class,cmd->{
            var h=head(s,no,true);BigDecimal delta=target.subtract(h.getReceivedQuantity());
            if(delta.signum()<0||target.add(h.getDifferenceQuantity()).compareTo(h.getExpectedQuantity())>0)throw conflict();
            if(delta.signum()==0)return view(h);
            if("CLOSED".equals(h.getStatus()))throw conflict();
            var b=lockBalance(s,h);change(s,actor,cmd,h,b,delta,"REPLENISH_RECEIVE",null);
            h.setReceivedQuantity(target);h.setStatus(target.compareTo(h.getExpectedQuantity())==0?"RECEIVED":"PARTIAL");
            save(h);return view(h);
        });
    }
    @Override public View close(Scope s,String actor,String key,String no,String reason){
        return command(s,actor,"REPLENISH_CLOSE",key,new Object[]{no,reason},View.class,cmd->{
            var h=head(s,no,true);if("CLOSED".equals(h.getStatus()))return view(h);
            h.setDifferenceQuantity(h.getExpectedQuantity().subtract(h.getReceivedQuantity()));h.setCloseReason(reason);
            h.setStatus("CLOSED");save(h);return view(h);
        });
    }
    @Override public View dispatchReturn(Scope s,String actor,String key,String no,BigDecimal target,String reason){
        positive(target);return command(s,actor,"REPLENISH_RETURN",key,new Object[]{no,canonical(target),reason},View.class,cmd->{
            var h=head(s,no,true);BigDecimal delta=target.subtract(h.getReturnedQuantity());
            if(delta.signum()<0||target.compareTo(h.getReceivedQuantity())>0)throw conflict();
            if(delta.signum()==0)return view(h);
            var b=lockBalance(s,h);change(s,actor,cmd,h,b,delta.negate(),"REPLENISH_RETURN",reason);
            h.setReturnedQuantity(target);save(h);return view(h);
        });
    }
    @Override public View ackReturn(Scope s,String actor,String key,String no,Inbound source){
        positive(source.quantity());return command(s,actor,"REPLENISH_RETURN_ACK",key,new Object[]{no,source},View.class,cmd->{
            var h=head(s,no,true);
            if(!h.getSourceSystem().equals(source.sourceSystem())||!h.getSourceOwner().equals(source.sourceOwner())
                    ||!h.getSourceItem().equals(source.itemNo())||!h.getSourceUnit().equals(source.baseUnit())
                    ||!no.equals(source.sourceRef())||source.lineNo()<1)throw conflict();
            var qty=positive(source.quantity().multiply(h.getFactor()));
            var old=returns.selectOne(Wrappers.<FnbStockReplenishmentReturn>lambdaQuery()
                    .eq(FnbStockReplenishmentReturn::getTenantNo,"MAIN").eq(FnbStockReplenishmentReturn::getSourceSystem,source.sourceSystem())
                    .eq(FnbStockReplenishmentReturn::getSourceOwner,source.sourceOwner()).eq(FnbStockReplenishmentReturn::getSourceDocument,source.documentNo())
                    .eq(FnbStockReplenishmentReturn::getSourceLine,source.lineNo()));
            if(old!=null){if(!no.equals(old.getReplenishmentNo())||qty.compareTo(old.getAcceptedQuantity())!=0)throw conflict();return view(h);}
            var total=h.getReturnAcceptedQuantity().add(qty);if(total.compareTo(h.getReturnedQuantity())>0)throw conflict();
            var row=new FnbStockReplenishmentReturn();audit(row,s,actor);row.setReplenishmentNo(no);
            row.setSourceSystem(source.sourceSystem());row.setSourceOwner(source.sourceOwner());row.setSourceDocument(source.documentNo());
            row.setSourceLine(source.lineNo());row.setAcceptedQuantity(qty);returns.insert(row);
            h.setReturnAcceptedQuantity(total);save(h);return view(h);
        });
    }
    @Override public View get(Scope s,String no){noOuter();return read.execute(tx->view(head(s,no,false)));}
    @Override public View findCommand(Scope s,String action,String key){
        noOuter();if(!action.startsWith("REPLENISH_"))throw conflict();
        return read.execute(tx->{var c=lookup(s,action,key);return c==null?null:result(c,View.class);});
    }
    @Override public Page list(Scope scope,long after,Long through,int size,boolean attentionOnly){
        noOuter();return read.execute(tx->{
            var query=Wrappers.<FnbStockReplenishment>lambdaQuery().eq(FnbStockReplenishment::getTenantNo,"MAIN")
                    .eq(FnbStockReplenishment::getEntityNo,scope.entityNo()).eq(FnbStockReplenishment::getStoreNo,scope.storeNo());
            long ceiling;
            if(through==null){var last=replenishments.selectOne(query.clone().orderByDesc(FnbStockReplenishment::getId).last("LIMIT 1"));ceiling=last==null?0:last.getId();}
            else ceiling=through;
            if(attentionOnly)query.and(q->q.apply("received_quantity + difference_quantity < expected_quantity")
                    .or().apply("return_accepted_quantity < returned_quantity").or().gt(FnbStockReplenishment::getDifferenceQuantity,BigDecimal.ZERO));
            var rows=replenishments.selectList(query.gt(FnbStockReplenishment::getId,after).le(FnbStockReplenishment::getId,ceiling)
                    .orderByAsc(FnbStockReplenishment::getId).last("LIMIT "+(size+1)));
            boolean more=rows.size()>size;var selected=more?rows.subList(0,size):rows;
            return new Page(selected.stream().map(FoodStockReplenishmentPortImpl::view).toList(),more?selected.getLast().getId():null,ceiling);
        });
    }
    @Override public View adoptManual(Scope scope,String actor,String key,String no,String movement){
        return command(scope,actor,"REPLENISH_MANUAL",key,new Object[]{no,movement},View.class,cmd->{var h=head(scope,no,true);linkManual(scope,actor,h,movement,false);return view(h);});
    }
    @Override public View registerManual(Scope scope,String actor,String key,Outbound source,Mapping mapping,String movement){
        positive(source.quantity());positive(mapping.factor());if(!scope.storeNo().equals(source.targetStoreNo()))throw conflict();
        return command(scope,actor,"REPLENISH_MANUAL_REGISTER",key,new Object[]{source,mapping,movement},View.class,cmd->{
            var c=connectionRow(scope,true);if(c!=null&&c.getSourceSystem()!=null&&!sameSource(c,source.sourceSystem(),source.sourceOwner()))throw conflict();
            var old=replenishments.selectOne(Wrappers.<FnbStockReplenishment>lambdaQuery().eq(FnbStockReplenishment::getTenantNo,"MAIN")
                    .eq(FnbStockReplenishment::getSourceSystem,source.sourceSystem()).eq(FnbStockReplenishment::getSourceOwner,source.sourceOwner())
                    .eq(FnbStockReplenishment::getSourceDocument,source.documentNo()).eq(FnbStockReplenishment::getSourceLine,source.lineNo()).last("FOR UPDATE"));
            if(old!=null){
                if(!scope.entityNo().equals(old.getEntityNo())||!scope.storeNo().equals(old.getStoreNo())||!"MANUAL_ONLY".equals(old.getAdmissionMode())
                        ||!mapping.itemNo().equals(old.getItemNo())||!mapping.baseUnit().equals(old.getBaseUnit())||mapping.factor().compareTo(old.getFactor())!=0
                        ||!source.itemNo().equals(old.getSourceItem())||!source.baseUnit().equals(old.getSourceUnit())||source.quantity().compareTo(old.getSourceQuantity())!=0)throw conflict();
                linkManual(scope,actor,old,movement,true);return view(old);
            }
            var h=new FnbStockReplenishment();audit(h,scope,actor);h.setReplenishmentNo(BizKey.next("FRP"));
            h.setSourceSystem(source.sourceSystem());h.setSourceOwner(source.sourceOwner());h.setSourceDocument(source.documentNo());h.setSourceLine(source.lineNo());
            h.setSourceItem(source.itemNo());h.setSourceUnit(source.baseUnit());h.setSourceQuantity(source.quantity());h.setItemNo(mapping.itemNo());
            h.setBaseUnit(mapping.baseUnit());h.setFactor(mapping.factor());h.setExpectedQuantity(positive(source.quantity().multiply(mapping.factor())));
            h.setReceivedQuantity(BigDecimal.ZERO);h.setDifferenceQuantity(BigDecimal.ZERO);h.setReturnedQuantity(BigDecimal.ZERO);h.setReturnAcceptedQuantity(BigDecimal.ZERO);
            h.setStatus("PENDING");h.setAdmissionGeneration(c==null?0:c.getGeneration());h.setAdmissionMode("MANUAL_ONLY");h.setSourcePostingSequence(source.postingSequence());
            replenishments.insert(h);linkManual(scope,actor,h,movement,true);return view(h);
        });
    }
    private void linkManual(Scope scope,String actor,FnbStockReplenishment h,String no,boolean close){
        var old=manuals.selectOne(Wrappers.<FnbStockReplenishmentManual>lambdaQuery().eq(FnbStockReplenishmentManual::getTenantNo,"MAIN")
                .eq(FnbStockReplenishmentManual::getMovementNo,no));
        if(old!=null){if(!h.getReplenishmentNo().equals(old.getReplenishmentNo()))throw conflict();return;}
        if("CLOSED".equals(h.getStatus())&&!(close&&"MANUAL_ONLY".equals(h.getAdmissionMode())))throw conflict();
        var movement=movements.selectOne(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN")
                .eq(FnbStockMovement::getEntityNo,scope.entityNo()).eq(FnbStockMovement::getStoreNo,scope.storeNo()).eq(FnbStockMovement::getMovementNo,no));
        if(movement==null||!"ADJUST_IN".equals(movement.getAction())||!h.getItemNo().equals(movement.getItemNo())
                ||!h.getBaseUnit().equals(movement.getBaseUnit())||movement.getQuantity()==null
                ||movement.getOnHandAfter().subtract(movement.getOnHandBefore()).compareTo(movement.getQuantity())!=0)throw conflict();
        var proof=commands.selectOne(Wrappers.<FnbStockCommand>lambdaQuery().eq(FnbStockCommand::getTenantNo,"MAIN")
                .eq(FnbStockCommand::getEntityNo,scope.entityNo()).eq(FnbStockCommand::getStoreNo,scope.storeNo())
                .eq(FnbStockCommand::getCommandNo,movement.getCommandNo()).eq(FnbStockCommand::getAction,"ADJUST").eq(FnbStockCommand::getStatus,"COMPLETED"));
        if(proof==null)throw conflict();var quantity=positive(movement.getQuantity());var total=h.getReceivedQuantity().add(quantity);
        if((close?total:total.add(h.getDifferenceQuantity())).compareTo(h.getExpectedQuantity())>0)throw conflict();
        var link=new FnbStockReplenishmentManual();audit(link,scope,actor);link.setReplenishmentNo(h.getReplenishmentNo());link.setMovementNo(no);link.setQuantity(quantity);manuals.insert(link);
        h.setReceivedQuantity(total);
        if(close){h.setStatus("CLOSED");h.setDifferenceQuantity(h.getExpectedQuantity().subtract(total));h.setCloseReason("MANUAL_RECEIPT_LINKED");}
        else h.setStatus(total.compareTo(h.getExpectedQuantity())==0?"RECEIVED":"PARTIAL");save(h);
    }
    @Override public Reconciliation reconcile(Scope scope,String no,Outbound source){
        noOuter();return read.execute(tx->{var h=head(scope,no,false);var issues=new ArrayList<String>();
            if(!h.getSourceSystem().equals(source.sourceSystem())||!h.getSourceOwner().equals(source.sourceOwner())
                    ||!h.getSourceDocument().equals(source.documentNo())||h.getSourceLine()!=source.lineNo()
                    ||!h.getSourceItem().equals(source.itemNo())||!h.getSourceUnit().equals(source.baseUnit())
                    ||h.getSourceQuantity().compareTo(source.quantity())!=0||!scope.storeNo().equals(source.targetStoreNo())
                    ||h.getSourcePostingSequence()!=null&&h.getSourcePostingSequence()!=source.postingSequence())issues.add("SOURCE_MISMATCH");
            var changes=movements.selectList(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN")
                    .eq(FnbStockMovement::getEntityNo,scope.entityNo()).eq(FnbStockMovement::getStoreNo,scope.storeNo()).eq(FnbStockMovement::getLineRef,no));
            BigDecimal receipts=changes.stream().filter(m->"REPLENISH_RECEIVE".equals(m.getAction())).map(FnbStockMovement::getQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal returned=changes.stream().filter(m->"REPLENISH_RETURN".equals(m.getAction())).map(FnbStockMovement::getQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal manual=manuals.selectList(Wrappers.<FnbStockReplenishmentManual>lambdaQuery().eq(FnbStockReplenishmentManual::getTenantNo,"MAIN")
                    .eq(FnbStockReplenishmentManual::getEntityNo,scope.entityNo()).eq(FnbStockReplenishmentManual::getStoreNo,scope.storeNo())
                    .eq(FnbStockReplenishmentManual::getReplenishmentNo,no)).stream().map(FnbStockReplenishmentManual::getQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal accepted=returns.selectList(Wrappers.<FnbStockReplenishmentReturn>lambdaQuery().eq(FnbStockReplenishmentReturn::getTenantNo,"MAIN")
                    .eq(FnbStockReplenishmentReturn::getEntityNo,scope.entityNo()).eq(FnbStockReplenishmentReturn::getStoreNo,scope.storeNo())
                    .eq(FnbStockReplenishmentReturn::getReplenishmentNo,no)).stream().map(FnbStockReplenishmentReturn::getAcceptedQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(receipts.add(manual).compareTo(h.getReceivedQuantity())!=0)issues.add("RECEIPT_LEDGER_MISMATCH");
            if(returned.compareTo(h.getReturnedQuantity())!=0)issues.add("RETURN_LEDGER_MISMATCH");if(accepted.compareTo(h.getReturnAcceptedQuantity())!=0)issues.add("RETURN_ACK_MISMATCH");
            if(h.getReceivedQuantity().add(h.getDifferenceQuantity()).compareTo(h.getExpectedQuantity())<0)issues.add("RECEIPT_PENDING");
            if(h.getDifferenceQuantity().signum()>0)issues.add("RECEIPT_DIFFERENCE");if(h.getReturnedQuantity().compareTo(h.getReturnAcceptedQuantity())>0)issues.add("RETURN_PENDING");
            return new Reconciliation(view(h),receipts,manual,returned,accepted,issues);
        });
    }
    private <T> T command(Scope s,String actor,String action,String key,Object payload,Class<T> type,Function<FnbStockCommand,T> work){
        noOuter();String snapshot=json.writeValueAsString(new Object[]{actor,action,payload});
        String hash=FoodStockFingerprint.sha256(snapshot);
        try{return write.execute(tx->{
            var old=lookup(s,action,key);if(old!=null){if(!Objects.equals(hash,old.getRequestHash()))throw conflict();return result(old,type);}
            var cmd=new FnbStockCommand();audit(cmd,s,actor);cmd.setCommandNo(BizKey.next("FSC"));cmd.setAction(action);
            cmd.setOperationKey(key);cmd.setRequestHash(hash);cmd.setRequestSnapshot(snapshot);cmd.setStatus("PROCESSING");commands.insert(cmd);
            T result=work.apply(cmd);cmd.setResultJson(json.writeValueAsString(result));cmd.setStatus("COMPLETED");
            if(commands.updateById(cmd)!=1)throw conflict();return result;
        });}catch(DuplicateKeyException|ConcurrencyFailureException race){throw conflict();}
    }
    private FnbStockCommand lookup(Scope s,String action,String key){return commands.selectOne(Wrappers.<FnbStockCommand>lambdaQuery()
            .eq(FnbStockCommand::getTenantNo,"MAIN").eq(FnbStockCommand::getEntityNo,s.entityNo()).eq(FnbStockCommand::getStoreNo,s.storeNo())
            .eq(FnbStockCommand::getAction,action).eq(FnbStockCommand::getOperationKey,key));}
    private <T> T result(FnbStockCommand cmd,Class<T> type){if(!"COMPLETED".equals(cmd.getStatus())||cmd.getResultJson()==null)throw conflict();return json.readValue(cmd.getResultJson(),type);}
    private FnbStockReplenishment head(Scope s,String no,boolean lock){
        var h=replenishments.selectOne(Wrappers.<FnbStockReplenishment>lambdaQuery().eq(FnbStockReplenishment::getTenantNo,"MAIN")
                .eq(FnbStockReplenishment::getEntityNo,s.entityNo()).eq(FnbStockReplenishment::getStoreNo,s.storeNo())
                .eq(FnbStockReplenishment::getReplenishmentNo,no).last(lock?"FOR UPDATE":""));
        if(h==null)throw BizException.of(ErrorCode.NOT_FOUND);return h;
    }
    private FnbStockBalance lockBalance(Scope s,FnbStockReplenishment h){
        var item=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                .eq(FnbStockItem::getEntityNo,s.entityNo()).eq(FnbStockItem::getItemNo,h.getItemNo()).last("FOR UPDATE"));
        if(item==null||!"ACTIVE".equals(item.getStatus())||!h.getBaseUnit().equals(item.getBaseUnit()))throw conflict();
        var b=balances.selectOne(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                .eq(FnbStockBalance::getEntityNo,s.entityNo()).eq(FnbStockBalance::getStoreNo,s.storeNo())
                .eq(FnbStockBalance::getItemNo,h.getItemNo()).last("FOR UPDATE"));
        if(b==null||!"ACTIVE".equals(b.getStatus())||!"TRACKED".equals(b.getTrackingMode()))throw conflict();return b;
    }
    private void change(Scope s,String actor,FnbStockCommand cmd,FnbStockReplenishment h,FnbStockBalance b,BigDecimal delta,String action,String reason){
        FoodLocalStockBalance before=new FoodLocalStockBalance(b.getOnHand(),b.getReservedQty()),after;
        try{after=before.adjust(delta);}catch(IllegalArgumentException invalid){throw conflict();}
        b.setOnHand(after.onHand());b.setUpdatedBy(actor);if(balances.updateById(b)!=1)throw conflict();
        var m=new FnbStockMovement();audit(m,s,actor);m.setEntityNo(s.entityNo());m.setStoreNo(s.storeNo());
        m.setMovementNo(BizKey.next("FSM"));m.setCommandNo(cmd.getCommandNo());m.setLineRef(h.getReplenishmentNo());
        m.setItemNo(h.getItemNo());m.setBaseUnit(h.getBaseUnit());m.setAction(action);m.setQuantity(delta.abs());
        m.setOnHandBefore(before.onHand());m.setOnHandAfter(after.onHand());m.setReservedBefore(before.reserved());m.setReservedAfter(after.reserved());
        m.setReasonDetail(reason);movements.insert(m);sync.capture(s.entityNo(),s.storeNo(),h.getItemNo());
    }
    private void save(FnbStockReplenishment h){if(replenishments.updateById(h)!=1)throw conflict();}
    private static View view(FnbStockReplenishment h){return new View(h.getReplenishmentNo(),h.getSourceSystem(),h.getSourceOwner(),h.getSourceDocument(),h.getSourceLine(),h.getSourceItem(),h.getSourceUnit(),h.getSourceQuantity(),h.getItemNo(),h.getBaseUnit(),h.getFactor(),h.getExpectedQuantity(),h.getReceivedQuantity(),h.getDifferenceQuantity(),h.getReturnedQuantity(),h.getReturnAcceptedQuantity(),h.getStatus());}
    private static BigDecimal positive(BigDecimal q){try{var checked=new FoodLocalStockBalance(q,BigDecimal.ZERO).onHand();if(checked.signum()==0)throw conflict();return checked;}catch(IllegalArgumentException invalid){throw conflict();}}
    private static String canonical(BigDecimal q){return q.stripTrailingZeros().toPlainString();}
    private static void noOuter(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Replenishment owns its local transaction");}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
    private static void audit(BaseEntity row,Scope s,String actor){
        row.setTenantNo("MAIN");row.setVersion(0L);row.setDeleted(0);row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());row.setCreatedBy(actor);row.setUpdatedBy(actor);
        if(row instanceof FnbStockReplenishment r){r.setEntityNo(s.entityNo());r.setStoreNo(s.storeNo());}
        if(row instanceof FnbStockReplenishmentReturn r){r.setEntityNo(s.entityNo());r.setStoreNo(s.storeNo());}
        if(row instanceof FnbStockReplenishmentConnection r){r.setEntityNo(s.entityNo());r.setStoreNo(s.storeNo());}
        if(row instanceof FnbStockReplenishmentManual r){r.setEntityNo(s.entityNo());r.setStoreNo(s.storeNo());}
        if(row instanceof FnbStockCommand r){r.setEntityNo(s.entityNo());r.setStoreNo(s.storeNo());}
    }
}
