package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.AdjustmentVO;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.Request;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.PolicyVO;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Save;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Intent;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.QuoteVO;
import ai.neargo.shop.trade.entity.OrdManualPriceAdjustment;
import ai.neargo.shop.trade.entity.OrdManualPricePolicy;
import ai.neargo.shop.trade.mapper.PricingManualMappers.AdjustmentMapper;
import ai.neargo.shop.trade.mapper.PricingManualMappers.PolicyMapper;
import ai.neargo.shop.trade.policy.PricingManualPlanCompiler;
import ai.neargo.shop.trade.service.PricingManualAdjustmentService;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real platform membership and an explicit seller policy admit each concession and subsequent revalidation. */
@Service
public class PricingManualAdjustmentServiceImpl implements PricingManualAdjustmentService {
    private final PolicyMapper policies;
    private final AdjustmentMapper adjustments;
    private final BizIdentityResolver identities;
    private final PricingPreparationService preparations;
    private final PricingQuoteService quotes;
    private final PricingQuoteAssembler assembler;
    private final IdempotencyService idem;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PricingManualPlanCompiler compiler=new PricingManualPlanCompiler();
    private final JsonMapper json=JsonMapper.builder().build();

    @Autowired
    public PricingManualAdjustmentServiceImpl(PolicyMapper policies,AdjustmentMapper adjustments,
            ObjectProvider<BizIdentityResolver> identities,PricingPreparationService preparations,
            PricingQuoteService quotes,PricingQuoteAssembler assembler,IdempotencyService idem,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(policies,adjustments,Objects.requireNonNullElse(identities.getIfUnique(),BizIdentityResolver.NONE),
                preparations,quotes,assembler,idem,manager,Clock.systemUTC());
    }
    public PricingManualAdjustmentServiceImpl(PolicyMapper policies,AdjustmentMapper adjustments,
            BizIdentityResolver identities,PricingPreparationService preparations,PricingQuoteService quotes,
            PricingQuoteAssembler assembler,IdempotencyService idem,PlatformTransactionManager manager,Clock clock) {
        this.policies=policies; this.adjustments=adjustments; this.identities=identities;
        this.preparations=preparations; this.quotes=quotes; this.assembler=assembler; this.idem=idem; this.clock=clock;
        tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override public PolicyVO getPolicy(Scope scope,String actor,String currency,String reason,ManualKind kind) {
        identity(scope,actor,true); key(currency,3); key(reason,32); if(kind==null) throw bad();
        return db(() -> {
            var rows=policies.selectList(policyQuery(scope,currency,reason,kind));
            if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            return policyView(rows.getFirst());
        });
    }

    @Override public PolicyVO savePolicy(Scope scope,String actor,String currency,String reason,ManualKind kind,
            Save req,String key) {
        identity(scope,actor,true); key(currency,3); key(reason,32); key(key,128);
        if(!currency.matches("[A-Z]{3}") || !reason.matches("[A-Z][A-Z0-9_]{0,31}")
                || kind==null || kind==ManualKind.ROUNDING || req==null || req.expectedVersion()==null
                || req.expectedVersion() < -1 || req.enabled()==null || req.selfLimitMinor()==null
                || req.maxLimitMinor()==null || req.selfLimitMinor()!=0 || req.maxLimitMinor()<0
                || req.maxLimitMinor()>MoneyMinor.MAX_JSON_SAFE_MINOR || req.minPayableRateBp()==null
                || req.minPayableRateBp()<0 || req.minPayableRateBp()>10000) throw bad();
        return write(scope,actor,key,"MANUAL_PRICE_POLICY",List.of(currency,reason,kind,req),PolicyVO.class,() -> {
            var rows=policies.selectList(policyQuery(scope,currency,reason,kind).last("FOR UPDATE"));
            OrdManualPricePolicy row;
            if(rows.isEmpty()) {
                if(req.expectedVersion()!=-1) throw conflict();
                row=new OrdManualPricePolicy(); row.setPolicyNo(BizKey.next("MPOL"));
                row.setTenantNo(scope.tenantNo()); row.setEntityNo(scope.entityNo()); row.setStoreNo(scope.storeNo());
                row.setCurrency(currency); row.setReasonCode(reason); row.setManualKind(kind.name());
                row.setCreatedBy(actor); row.setVersion(0L);
            } else {
                row=rows.getFirst(); if(!req.expectedVersion().equals(row.getVersion())) throw conflict();
            }
            row.setEnabled(req.enabled()); row.setSelfLimitMinor(req.selfLimitMinor());
            row.setMaxLimitMinor(req.maxLimitMinor()); row.setMinPayableRateBp(req.minPayableRateBp());
            row.setUpdatedBy(actor);
            if((rows.isEmpty()?policies.insert(row):policies.updateById(row))!=1) throw conflict();
            return policyView(row);
        });
    }

    @Override public AdjustmentVO propose(Scope scope,String actor,Request req,String key) {
        var proposer=identity(scope,actor,false); key(key,128);
        if(req==null || req.source()==null || req.kind()==null || req.kind()==ManualKind.ROUNDING) throw bad();
        key(req.reasonCode(),32); key(req.reason(),255);
        String operationKey=PricingSnapshotHash.of(List.of("MANUAL_PRICE_PROPOSE",key));
        String requestHash=PricingSnapshotHash.of(List.of(scope,actor,req));
        var existing=db(() -> adjustments.selectList(scopeQuery(scope)
                .eq(OrdManualPriceAdjustment::getOperationKey,operationKey)));
        if(!existing.isEmpty()) return replay(existing.getFirst(),actor,requestHash);
        var prepared=preparations.staff(scope,req.source());
        if(!scope.equals(prepared.source().scope())
                || !req.source().equals(prepared.source().source())) throw conflict();
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS); String no=BizKey.next("MPRC");
        final PricingManualPlanCompiler.Assessment assessment;
        try { assessment=compiler.assess(prepared,actor,req.kind(),req.amountMinor(),req.payableRateBp(),
                req.reasonCode(),no,now); }
        catch(IllegalArgumentException|ArithmeticException invalid) { throw unsupported(); }
        var policy=loadPolicy(scope,prepared.source().prices().currency(),req.reasonCode(),req.kind(),false);
        limits(policy,assessment.discountMinor(),prepared.source().prices().baseTotal().minor());
        Instant expires=(prepared.expiresAt().isBefore(now.plusSeconds(120))
                ?prepared.expiresAt():now.plusSeconds(120)).truncatedTo(ChronoUnit.MILLIS);
        if(!now.plusSeconds(1).isBefore(expires)) throw conflict();
        preparations.confirmStaff(scope,req.source(),prepared.preparationHash());
        return transaction(() -> {
            var current=loadPolicy(scope,policy.getCurrency(),req.reasonCode(),req.kind(),true);
            if(!policy.getPolicyNo().equals(current.getPolicyNo())
                    || !policy.getVersion().equals(current.getVersion())) throw conflict();
            var found=adjustments.selectList(scopeQuery(scope)
                    .eq(OrdManualPriceAdjustment::getOperationKey,operationKey));
            if(!found.isEmpty()) return replay(found.getFirst(),actor,requestHash);
            var row=new OrdManualPriceAdjustment(); row.setTenantNo(scope.tenantNo());
            row.setAdjustmentNo(no); row.setEntityNo(scope.entityNo()); row.setStoreNo(scope.storeNo());
            row.setActorNo(actor); row.setSubjectNo(prepared.source().subjectNo());
            row.setManualKind(req.kind().name());
            row.setRequestedMinor(req.amountMinor()); row.setPayableRateBp(req.payableRateBp());
            row.setCurrency(policy.getCurrency()); row.setDiscountMinor(assessment.discountMinor());
            row.setReasonCode(req.reasonCode()); row.setReason(req.reason());
            row.setSourceJson(json.writeValueAsString(req.source()));
            row.setPreparationHash(prepared.preparationHash());
            row.setConfigHash(prepared.rules().config().configHash());
            row.setTaxHash(PricingSnapshotHash.of(prepared.tax()));
            row.setPolicyNo(policy.getPolicyNo()); row.setPolicyVersion(policy.getVersion());
            row.setOperationKey(operationKey); row.setRequestHash(requestHash);
            row.setEvaluatedAt(utc(now)); row.setExpiresAt(utc(expires));
            // No trusted cumulative business-day quota exists yet: employees never self-authorize nonzero amounts.
            boolean ownerOriginated=proposer.owner();
            row.setStatus(ownerOriginated?"APPROVED":"PENDING");
            row.setApprovalMode(ownerOriginated?"OWNER_ORIGINATED":"OWNER_REVIEW");
            if(ownerOriginated) { row.setApprovedBy(actor); row.setApprovedAt(utc(now)); }
            row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setVersion(0L);
            if(adjustments.insert(row)!=1) throw conflict();
            return view(row);
        });
    }

    @Override public AdjustmentVO get(Scope scope,String actor,String no) {
        identity(scope,actor,false); return view(load(scope,no,false));
    }
    @Override public AdjustmentVO approve(Scope scope,String actor,String no,long version,String key) {
        identity(scope,actor,true); key(no,32); key(key,128); if(version<0) throw bad();
        var row=load(scope,no,false);
        if(actor.equals(row.getActorNo())) throw forbidden();
        var source=json.readValue(row.getSourceJson(),PricingSourceRef.class);
        var prepared=preparations.staff(scope,source); admit(scope,row,prepared,false);
        preparations.confirmStaff(scope,source,prepared.preparationHash());
        return write(scope,actor,key,"MANUAL_PRICE_APPROVE",List.of(no,version),AdjustmentVO.class,() -> {
            var policy=loadPolicy(scope,row.getCurrency(),row.getReasonCode(),
                    ManualKind.valueOf(row.getManualKind()),true);
            if(!policy.getPolicyNo().equals(row.getPolicyNo()) || !policy.getVersion().equals(row.getPolicyVersion()))
                throw conflict();
            var locked=load(scope,no,true);
            if("APPROVED".equals(locked.getStatus()) && actor.equals(locked.getApprovedBy())) return view(locked);
            if(!"PENDING".equals(locked.getStatus()) || locked.getVersion()!=version
                    || !fresh(locked)) throw conflict();
            locked.setStatus("APPROVED"); locked.setApprovedBy(actor); locked.setApprovedAt(utc(clock.instant()));
            locked.setUpdatedBy(actor); if(adjustments.updateById(locked)!=1) throw conflict();
            return view(locked);
        });
    }

    @Override public PricingCheckoutPort.QuoteVO quote(Scope scope,String actor,String no,String key) {
        outside(); identity(scope,actor,false); key(key,128);
        var row=load(scope,no,false); if(!actor.equals(row.getActorNo())) throw forbidden();
        var source=json.readValue(row.getSourceJson(),PricingSourceRef.class);
        var prepared=preparations.staff(scope,source); admit(scope,row,prepared,true);
        var context=new Context(scope.tenantNo(),scope.entityNo(),scope.storeNo(),row.getSubjectNo(),actor);
        var command=compile(prepared,row);
        preparations.confirmStaff(scope,source,prepared.preparationHash());
        var stored=quotes.save(context,command,key);
        var current=preparations.confirmStaff(scope,source,prepared.preparationHash());
        stored=quotes.get(context,stored.quoteNo()); verify(current,context,stored);
        return quoteView(stored);
    }

    @Override public boolean handles(QuoteVO stored) {
        var intent=json.treeToValue(json.readTree(stored.planSnapshot()).get("intent"),Intent.class);
        return intent.selection().fixedBenefits().stream()
                .anyMatch(b -> PricingManualPlanCompiler.OWNER.equals(b.asset().owner()));
    }
    @Override public void verify(Prepared prepared,Context context,QuoteVO stored) {
        outside();
        var scope=new Scope(context.tenantNo(),context.entityNo(),context.storeNo());
        var intent=json.treeToValue(json.readTree(stored.planSnapshot()).get("intent"),Intent.class);
        if(intent.selection().fixedBenefits().size()!=1 || !handles(stored)) throw conflict();
        String no=intent.selection().fixedBenefits().getFirst().asset().assetNo();
        var row=load(scope,no,false);
        if(!Objects.equals(context.actorNo(),row.getActorNo())
                || !Objects.equals(context.subjectNo(),row.getSubjectNo())
                || !"READY".equals(stored.status()) || !clock.instant().isBefore(Instant.parse(stored.expiresAt())))
            throw conflict();
        admit(scope,row,prepared,true);
        var rebuilt=assembler.prepare(context,compile(prepared,row),prepared.rules().config().canonicalJson());
        if(!stored.factsHash().equals(rebuilt.factsHash()) || !stored.requestHash().equals(rebuilt.requestHash())
                || !stored.economicsHash().equals(rebuilt.economicsHash())) throw conflict();
    }

    private void admit(Scope scope,OrdManualPriceAdjustment row,Prepared prepared,boolean approved) {
        identity(scope,row.getActorNo(),false);
        if(!fresh(row) || !scope.equals(prepared.source().scope())
                || !json.readValue(row.getSourceJson(),PricingSourceRef.class).equals(prepared.source().source())
                || !Objects.equals(row.getSubjectNo(),prepared.source().subjectNo())
                || !row.getPreparationHash().equals(prepared.preparationHash())
                || !row.getConfigHash().equals(prepared.rules().config().configHash())
                || !row.getTaxHash().equals(PricingSnapshotHash.of(prepared.tax()))) throw conflict();
        var policy=loadPolicy(scope,row.getCurrency(),row.getReasonCode(),
                ManualKind.valueOf(row.getManualKind()),false);
        if(!row.getPolicyNo().equals(policy.getPolicyNo()) || !row.getPolicyVersion().equals(policy.getVersion()))
            throw conflict();
        limits(policy,row.getDiscountMinor(),prepared.source().prices().baseTotal().minor());
        if(approved) {
            if(!"APPROVED".equals(row.getStatus()) || row.getApprovedAt()==null) throw conflict();
            if("OWNER_ORIGINATED".equals(row.getApprovalMode())) {
                if(!row.getActorNo().equals(row.getApprovedBy())) throw conflict();
                identity(scope,row.getActorNo(),true);
            } else if("OWNER_REVIEW".equals(row.getApprovalMode())) {
                if(row.getActorNo().equals(row.getApprovedBy())) throw conflict();
                identity(scope,row.getApprovedBy(),true);
            } else throw conflict();
        }
    }
    private PricingQuoteCommands.SaveReq compile(Prepared prepared,OrdManualPriceAdjustment row) {
        try { return compiler.compile(prepared,row,clock.instant()); }
        catch(IllegalArgumentException|ArithmeticException invalid) { throw unsupported(); }
    }
    private BizContext identity(Scope scope,String actor,boolean owner) {
        if(scope==null || !"MAIN".equals(scope.tenantNo())) throw forbidden(); key(actor,32);
        var identity=identities.resolve(actor,scope.storeNo());
        if(identity==null || !scope.entityNo().equals(identity.merchantNo())
                || !scope.storeNo().equals(identity.currentStoreNo()) || !identity.storeNos().contains(scope.storeNo())
                || (owner?!identity.owner():!identity.can(BizPerms.CAMPAIGN))) throw forbidden();
        return identity;
    }
    private void limits(OrdManualPricePolicy policy,long discount,long base) {
        if(!Boolean.TRUE.equals(policy.getEnabled()) || policy.getSelfLimitMinor()!=0
                || discount<=0 || discount>policy.getMaxLimitMinor()
                || BigInteger.valueOf(base-discount).multiply(BigInteger.valueOf(10000)).compareTo(
                    BigInteger.valueOf(base).multiply(BigInteger.valueOf(policy.getMinPayableRateBp())))<0)
            throw forbidden();
    }
    private OrdManualPricePolicy loadPolicy(Scope scope,String currency,String reason,ManualKind kind,boolean lock) {
        return db(() -> {
            var query=policyQuery(scope,currency,reason,kind); if(lock) query.last("FOR UPDATE");
            var rows=policies.selectList(query);
            if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().getEnabled())) throw unsupported();
            return rows.getFirst();
        });
    }
    private LambdaQueryWrapper<OrdManualPricePolicy> policyQuery(
            Scope scope,String currency,String reason,ManualKind kind) {
        return Wrappers.<OrdManualPricePolicy>lambdaQuery().eq(OrdManualPricePolicy::getTenantNo,scope.tenantNo())
                .eq(OrdManualPricePolicy::getEntityNo,scope.entityNo())
                .eq(OrdManualPricePolicy::getStoreNo,scope.storeNo())
                .eq(OrdManualPricePolicy::getCurrency,currency).eq(OrdManualPricePolicy::getReasonCode,reason)
                .eq(OrdManualPricePolicy::getManualKind,kind.name());
    }
    private LambdaQueryWrapper<OrdManualPriceAdjustment> scopeQuery(Scope scope) {
        return Wrappers.<OrdManualPriceAdjustment>lambdaQuery()
                .eq(OrdManualPriceAdjustment::getTenantNo,scope.tenantNo())
                .eq(OrdManualPriceAdjustment::getEntityNo,scope.entityNo())
                .eq(OrdManualPriceAdjustment::getStoreNo,scope.storeNo());
    }
    private OrdManualPriceAdjustment load(Scope scope,String no,boolean lock) {
        key(no,32);
        return db(() -> {
            var query=scopeQuery(scope).eq(OrdManualPriceAdjustment::getAdjustmentNo,no);
            if(lock) query.last("FOR UPDATE");
            var rows=adjustments.selectList(query); if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            return rows.getFirst();
        });
    }
    private AdjustmentVO replay(OrdManualPriceAdjustment row,String actor,String hash) {
        if(!actor.equals(row.getActorNo()) || !hash.equals(row.getRequestHash())) throw conflict(); return view(row);
    }
    private boolean fresh(OrdManualPriceAdjustment row) {
        return row.getExpiresAt()!=null && clock.instant().isBefore(row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
    private AdjustmentVO view(OrdManualPriceAdjustment row) {
        return new AdjustmentVO(row.getAdjustmentNo(),fresh(row)?row.getStatus():"EXPIRED",row.getActorNo(),
                row.getManualKind(),row.getCurrency(),row.getDiscountMinor(),row.getReasonCode(),row.getReason(),
                row.getApprovedBy(),row.getExpiresAt().toInstant(ZoneOffset.UTC).toString(),
                row.getPolicyVersion(),row.getVersion());
    }
    private PolicyVO policyView(OrdManualPricePolicy row) {
        return new PolicyVO(row.getPolicyNo(),row.getCurrency(),row.getReasonCode(),row.getManualKind(),
                Boolean.TRUE.equals(row.getEnabled()),row.getSelfLimitMinor(),row.getMaxLimitMinor(),
                row.getMinPayableRateBp(),row.getVersion());
    }
    private PricingCheckoutPort.QuoteVO quoteView(QuoteVO stored) {
        var source=json.readTree(stored.factsSnapshot()).path("facts");
        var result=json.readTree(stored.resultSnapshot()); var lines=new ArrayList<PricingCheckoutPort.LineVO>();
        for(var line:result.path("lines")) {
            var unit=line.path("source"); var a=line.path("amounts");
            lines.add(new PricingCheckoutPort.LineVO(unit.path("sourceLineNo").asText(),
                    nullable(unit.path("componentRef")),
                    unit.path("taxInvoiceLineRef").asText(),line.path("kind").asText(),unit.path("goodsNo").asText(),
                    unit.path("skuNo").asText(),unit.path("quantityStart").decimalValue(),
                    unit.path("quantityEnd").decimalValue(),
                    a.path("goodsMinor").asLong(),a.path("netMinor").asLong(),a.path("taxMinor").asLong(),
                    a.path("receivableMinor").asLong()));
        }
        var a=result.path("amounts"); long discount=Math.addExact(a.path("manualDiscountMinor").asLong(),
                a.path("waiverMinor").asLong());
        var total=new PricingCheckoutPort.TotalsVO(a.path("goodsMinor").asLong(),discount,a.path("netMinor").asLong(),
                a.path("taxMinor").asLong(),a.path("receivableMinor").asLong(),
                result.path("remainingDueMinor").asLong());
        String digest=PricingSnapshotHash.of(List.of("PRICING_CONFIRMATION_V1",stored.quoteNo(),stored.economicsHash(),
                stored.expiresAt()));
        return new PricingCheckoutPort.QuoteVO(stored.quoteNo(),stored.status(),stored.expiresAt(),
                source.path("sourceVersion").asLong(),source.path("sourceSnapshotHash").asText(),
                source.path("currency").asText(),lines,total,digest);
    }
    private <T> T write(Scope scope,String actor,String key,String action,Object request,
            Class<T> type,Supplier<T> work) {
        String operationScope=action+":"+scope.entityNo()+":"+scope.storeNo();
        return transaction(() -> idem.execute(key,operationScope,actor,PricingSnapshotHash.of(request),type,work));
    }
    private <T> T transaction(Supplier<T> work) {
        outside();
        try { return tx.execute(status -> db(work)); }
        catch(ConcurrencyFailureException|DuplicateKeyException failure) { throw conflict(); }
    }
    private static <T> T db(Supplier<T> work) { return DataScopeContext.executeWithoutScope(work); }
    private static void outside() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported();
    }
    private static String nullable(JsonNode node) { return node.isMissingNode()||node.isNull()?null:node.asText(); }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant,ZoneOffset.UTC); }
    private static void key(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max) throw bad();
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException forbidden() { return BizException.of(ErrorCode.FORBIDDEN); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
