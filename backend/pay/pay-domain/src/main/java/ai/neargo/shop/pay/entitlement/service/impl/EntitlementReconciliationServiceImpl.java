package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Units;
import ai.neargo.shop.pay.entitlement.entity.EntGrant;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntPeriodBucket;
import ai.neargo.shop.pay.entitlement.entity.PayEntReconRun;
import ai.neargo.shop.pay.entitlement.entity.PayEntReconDiff;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService;
import ai.neargo.shop.pay.mapper.entitlement.PayEntReconRunMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntReconDiffMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntGrantMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntReconciliationFactsMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

/** Local pay snapshots only: comparison records discrepancies and never adjusts resources or invokes sources. */
@Service
public class EntitlementReconciliationServiceImpl implements EntitlementReconciliationService {
    private final PayEntReconRunMapper runs;
    private final PayEntReconDiffMapper diffs;
    private final EntInstanceMapper instances;
    private final EntLedgerMapper ledger;
    private final EntGrantMapper grants;
    private final EntPeriodBucketMapper buckets;
    private final SvAccountMapper accounts;
    private final EntReconciliationFactsMapper facts;
    private final ObjectMapper json;

    public EntitlementReconciliationServiceImpl(PayEntReconRunMapper runs,PayEntReconDiffMapper diffs,
            EntInstanceMapper instances,EntLedgerMapper ledger,EntGrantMapper grants,EntPeriodBucketMapper buckets,
            SvAccountMapper accounts,EntReconciliationFactsMapper facts,ObjectMapper json) {
        this.runs=runs; this.diffs=diffs; this.instances=instances; this.ledger=ledger; this.grants=grants;
        this.buckets=buckets; this.accounts=accounts; this.facts=facts; this.json=json;
    }

    @Override
    @Transactional(value="payTxManager",isolation=Isolation.REPEATABLE_READ)
    public RunView checkRights(CatalogScope scope,CheckRights c,String key) {
        if(c==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        return check(scope,new Check("RIGHTS",null,c.bizDate(),c.marketZone(),c.afterInstanceNo(),c.limit()),key);
    }

    @Override
    @Transactional(value="payTxManager",isolation=Isolation.REPEATABLE_READ)
    public RunView check(CatalogScope scope,Check c,String key) {
        platform(scope);
        if(c==null || key==null || key.isBlank() || key.length()>64) throw BizException.of(ErrorCode.BAD_REQUEST);
        String hash=EntitlementRecords.digest(json.writeValueAsString(List.of(scope.entityNo(),scope.actorNo(),c)));
        var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,hash);
        var candidate=new PayEntReconRun(); EntitlementRecords.audit(candidate,ctx);
        candidate.setReconNo("RR"+EntitlementRecords.digest(scope.tenantNo()+":"+key).substring(0,60));
        candidate.setRequestKey(key); candidate.setPayloadHash(hash); candidate.setEntityNo(scope.entityNo());
        candidate.setAxis(c.axis()); candidate.setCurrency(c.currency()); candidate.setBizDate(c.bizDate());
        candidate.setMarketZone(c.marketZone()); candidate.setStartedAt(EntitlementRecords.now());
        runs.claim(candidate);
        var run=runs.lock(scope.tenantNo(),key);
        if(!hash.equals(run.getPayloadHash())) throw BizException.of(ErrorCode.CONFLICT);
        if("COMPLETED".equals(run.getStatus())) return view(run);
        List<Comparison> comparisons=switch(c.axis()) {
            case "MONEY" -> money(scope,c);
            case "ISSUANCE" -> issuance(scope,c);
            default -> rights(scope,c);
        };
        boolean more=comparisons.size()>c.limit();
        if(more) comparisons=comparisons.subList(0,c.limit());
        long differences=0;
        for(var comparison:comparisons) {
            if(Objects.equals(comparison.expected(),comparison.actual())) continue;
            differences++;
            var diff=new PayEntReconDiff(); EntitlementRecords.audit(diff,ctx);
            diff.setDiffNo("RD"+EntitlementRecords.digest(run.getReconNo()+":"+comparison.no()).substring(0,60));
            diff.setReconNo(run.getReconNo()); diff.setEntityNo(scope.entityNo()); diff.setResourceNo(comparison.no());
            diff.setDiffKind(c.axis()+"_MISMATCH"); diff.setStatus("OPEN");
            diff.setExpectedSnapshot(json.writeValueAsString(comparison.expected()));
            diff.setActualSnapshot(json.writeValueAsString(comparison.actual()));
            EntitlementRecords.changed(diffs.insert(diff));
        }
        run.setStatus("COMPLETED"); run.setCheckedCount((long)comparisons.size()); run.setDiffCount(differences);
        run.setScanCursor(more?comparisons.getLast().no():null); run.setCompletedAt(EntitlementRecords.now());
        run.setUpdatedAt(run.getCompletedAt()); run.setUpdatedBy(scope.actorNo());
        EntitlementRecords.changed(runs.updateById(run));
        return view(run);
    }

    private List<Comparison> rights(CatalogScope scope,Check c) {
        return instances.selectList(Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo,scope.tenantNo()).eq(EntInstance::getEntityNo,scope.entityNo())
                .gt(EntInstance::getInstanceNo,c.afterResourceNo()).orderByAsc(EntInstance::getInstanceNo)
                .last("LIMIT "+(c.limit()+1))).stream().map(asset -> {
                    if("HOLD".equals(c.axis())) return holds(scope,asset);
                    var totals=ledger.totals(scope.tenantNo(),asset.getInstanceNo());
                    return new Comparison(asset.getInstanceNo(),
                            new Units(asset.getTotalUnits()==null?null:number(totals,"units"),
                                    number(totals,"reserved"),number(totals,"seq")),
                            new Units(asset.getRemainingUnits()==null?null:asset.getRemainingUnits().longValue(),
                                    asset.getReservedUnits(),asset.getAssetSeq()));
                }).toList();
    }

    private Comparison holds(CatalogScope scope,EntInstance asset) {
        var expected=new LinkedHashMap<String,Object>();
        var actual=new LinkedHashMap<String,Object>();
        expected.put("reserved",number(facts.rightsHolds(scope.tenantNo(),asset.getInstanceNo()),"reserved"));
        actual.put("reserved",asset.getReservedUnits().longValue());
        var definition=json.readValue(asset.getRuleSnapshot(),EntitlementDefinition.class);
        if("PERIOD_DAILY".equals(definition.usageModel())) {
            var expectedBuckets=new LinkedHashMap<String,Object>();
            var actualBuckets=new LinkedHashMap<String,Object>();
            for(var row:facts.bucketGroups(scope.tenantNo(),asset.getInstanceNo())) {
                expectedBuckets.put(row.get("period").toString()+":"+row.get("service_day_key"),
                        List.of(number(row,"reserved"),number(row,"used"),definition.dailyUnits().longValue()));
            }
            for(var bucket:buckets.selectList(Wrappers.<EntPeriodBucket>lambdaQuery()
                    .eq(EntPeriodBucket::getTenantNo,scope.tenantNo())
                    .eq(EntPeriodBucket::getInstanceNo,asset.getInstanceNo()))) {
                actualBuckets.put(bucket.getPeriodKey()+":"+bucket.getServiceDay(),
                        List.of(bucket.getReservedUnits().longValue(),bucket.getUsedUnits().longValue(),
                                bucket.getLimitUnits().longValue()));
            }
            expected.put("buckets",expectedBuckets); actual.put("buckets",actualBuckets);
        }
        return new Comparison(asset.getInstanceNo(),expected,actual);
    }

    private List<Comparison> issuance(CatalogScope scope,Check c) {
        return grants.selectList(Wrappers.<EntGrant>lambdaQuery().eq(EntGrant::getTenantNo,scope.tenantNo())
                .eq(EntGrant::getEntityNo,scope.entityNo()).gt(EntGrant::getGrantNo,c.afterResourceNo())
                .orderByAsc(EntGrant::getGrantNo).last("LIMIT "+(c.limit()+1))).stream().map(grant -> {
                    long expected="COMMITTED".equals(grant.getStatus())?grant.getQuantity():0;
                    var issued=instances.selectList(Wrappers.<EntInstance>lambdaQuery()
                            .eq(EntInstance::getTenantNo,scope.tenantNo()).eq(EntInstance::getGrantNo,grant.getGrantNo()));
                    long matching=issued.stream().filter(a -> Objects.equals(a.getEntityNo(),grant.getEntityNo())
                            && Objects.equals(a.getUserNo(),grant.getUserNo())
                            && Objects.equals(a.getTemplateVersionNo(),grant.getTemplateVersionNo())).count();
                    return new Comparison(grant.getGrantNo(),List.of(expected,expected),List.of((long)issued.size(),matching));
                }).toList();
    }

    private List<Comparison> money(CatalogScope scope,Check c) {
        return accounts.selectList(Wrappers.<SvAccount>lambdaQuery().eq(SvAccount::getTenantNo,scope.tenantNo())
                .eq(SvAccount::getEntityNo,scope.entityNo()).eq(SvAccount::getCurrency,c.currency())
                .gt(SvAccount::getAccountNo,c.afterResourceNo()).orderByAsc(SvAccount::getAccountNo)
                .last("LIMIT "+(c.limit()+1))).stream().map(account -> {
                    String tenant=scope.tenantNo(),no=account.getAccountNo();
                    var vouchers=facts.vouchers(tenant,no); var lots=facts.lots(tenant,no);
                    var entries=facts.entries(tenant,no); var held=facts.moneyHolds(tenant,no);
                    long principal=number(vouchers,"principal"),bonus=number(vouchers,"bonus");
                    long hp=number(held,"principal"),hb=number(held,"bonus");
                    var expected=new LinkedHashMap<String,Object>(); var actual=new LinkedHashMap<String,Object>();
                    expected.put("account",List.of(principal,bonus,hp,hb,number(vouchers,"seq")));
                    actual.put("account",List.of(account.getPrincipalMinor(),account.getBonusMinor(),
                            account.getHeldPrincipalMinor(),account.getHeldBonusMinor(),account.getAccountSeq()));
                    expected.put("sequenceCount",account.getAccountSeq());
                    actual.put("sequenceCount",number(vouchers,"entries"));
                    expected.put("lots",List.of(principal,bonus,hp,hb));
                    actual.put("lots",List.of(number(lots,"principal"),number(lots,"bonus"),
                            number(lots,"held_principal"),number(lots,"held_bonus")));
                    expected.put("entries",List.of(principal,bonus));
                    actual.put("entries",List.of(number(entries,"principal"),number(entries,"bonus")));
                    var expectedJournal=new LinkedHashMap<String,Long>();
                    expectedJournal.put("PRINCIPAL:CUSTOMER_CLAIM",principal);
                    expectedJournal.put("PRINCIPAL:MERCHANT_OBLIGATION",-principal);
                    expectedJournal.put("BONUS:CUSTOMER_CLAIM",bonus);
                    expectedJournal.put("BONUS:MERCHANT_OBLIGATION",-bonus);
                    var actualJournal=new LinkedHashMap<String,Long>();
                    expectedJournal.keySet().forEach(k -> actualJournal.put(k,0L));
                    for(var line:facts.journal(tenant,no)) {
                        actualJournal.put(line.get("pool")+":"+line.get("book"),number(line,"net"));
                    }
                    expected.put("journal",expectedJournal); actual.put("journal",actualJournal);
                    expected.put("lotDifferences",0L); actual.put("lotDifferences",facts.lotDifferences(tenant,no));
                    expected.put("voucherDifferences",0L);
                    actual.put("voucherDifferences",facts.voucherDifferences(tenant,no));
                    return new Comparison(no,expected,actual);
                }).toList();
    }

    @Override public RunView get(CatalogScope scope,String no) { return view(find(scope,no)); }

    private PayEntReconRun find(CatalogScope scope,String no) {
        platform(scope);
        if(no==null || no.isBlank() || no.length()>64) throw BizException.of(ErrorCode.BAD_REQUEST);
        var row=runs.selectOne(Wrappers.<PayEntReconRun>lambdaQuery().eq(PayEntReconRun::getTenantNo,scope.tenantNo())
                .eq(PayEntReconRun::getEntityNo,scope.entityNo()).eq(PayEntReconRun::getReconNo,no));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    @Override public PageData<RunView> list(CatalogScope scope,LocalDate date,long page,long size) {
        platform(scope); page(page,size);
        var result=runs.selectPage(new Page<>(page,size),Wrappers.<PayEntReconRun>lambdaQuery()
                .eq(PayEntReconRun::getTenantNo,scope.tenantNo()).eq(PayEntReconRun::getEntityNo,scope.entityNo())
                .eq(date!=null,PayEntReconRun::getBizDate,date).orderByDesc(PayEntReconRun::getId));
        return new PageData<>(result.getRecords().stream().map(EntitlementReconciliationServiceImpl::view).toList(),
                result.getTotal(),page,size);
    }

    @Override public PageData<DiffView> differences(CatalogScope scope,String no,long page,long size) {
        find(scope,no); page(page,size);
        var result=diffs.selectPage(new Page<>(page,size),Wrappers.<PayEntReconDiff>lambdaQuery()
                .eq(PayEntReconDiff::getTenantNo,scope.tenantNo()).eq(PayEntReconDiff::getEntityNo,scope.entityNo())
                .eq(PayEntReconDiff::getReconNo,no).orderByAsc(PayEntReconDiff::getId));
        return new PageData<>(result.getRecords().stream().map(d -> new DiffView(d.getDiffNo(),d.getReconNo(),
                d.getEntityNo(),d.getResourceNo(),d.getDiffKind(),d.getStatus())).toList(),result.getTotal(),page,size);
    }

    private static void page(long page,long size) {
        if(page<1 || page>1000000 || size<1 || size>100) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static long number(Map<String,Object> row,String key) {
        return new BigDecimal(row.get(key).toString()).longValueExact();
    }
    private static RunView view(PayEntReconRun row) {
        return new RunView(row.getReconNo(),row.getEntityNo(),row.getAxis(),row.getStatus(),row.getCheckedCount(),
                row.getDiffCount(),row.getScanCursor(),row.getBizDate(),row.getMarketZone(),row.getCurrency());
    }
    private static void platform(CatalogScope scope) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private record Comparison(String no,Object expected,Object actual) { }
}
