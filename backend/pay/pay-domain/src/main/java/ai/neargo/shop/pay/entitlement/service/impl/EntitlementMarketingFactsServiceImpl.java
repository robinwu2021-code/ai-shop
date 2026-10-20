package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.PreparedGrant;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.service.EntitlementMarketingFactsService;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntGrantMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMarketingFactMapper;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.Attribution;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.UsageEffects;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

/** Original use facts and lot/group attributions share one read snapshot; no core table or financial write is used. */
@Service
public class EntitlementMarketingFactsServiceImpl implements EntitlementMarketingFactsService {
    private final EntUsageMapper usages;
    private final EntUseIntentMapper intents;
    private final EntInstanceMapper instances;
    private final EntGrantMapper grants;
    private final EntMarketingFactMapper facts;
    private final ObjectMapper json;
    public EntitlementMarketingFactsServiceImpl(EntUsageMapper usages,EntUseIntentMapper intents,
            EntInstanceMapper instances,EntGrantMapper grants,EntMarketingFactMapper facts,ObjectMapper json) {
        this.usages=usages; this.intents=intents; this.instances=instances; this.grants=grants;
        this.facts=facts; this.json=json;
    }
    @Override
    @Transactional(value="payTxManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public UsageEffects usage(EntitlementContext ctx,String entity,String no,String after,int limit) {
        if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        ContractChecks.id(entity); ContractChecks.id(no);
        ContractChecks.require(after!=null && after.length()<=64 && after.equals(after.strip()) && limit>=1 && limit<=100);
        var usage=usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo,ctx.tenantNo())
                .eq(EntUsage::getEntityNo,entity).eq(EntUsage::getUsageNo,no).eq(EntUsage::getStatus,"COMMITTED"));
        if(usage==null) throw BizException.of(ErrorCode.NOT_FOUND);
        var intent=intents.find(ctx.tenantNo(),usage.getOperationNo());
        if(intent==null) throw BizException.of(ErrorCode.CONFLICT);
        var command=json.readValue(intent.getCommandSnapshot(),Prepared.class).command();
        boolean money="STORED_VALUE".equals(usage.getAssetKind());
        String asset=money?command.accountNo():command.instanceNo();
        List<Attribution> rows;
        String next=null;
        if(money) {
            long total=facts.moneyTotal(ctx.tenantNo(),entity,usage.getOperationNo(),usage.getCurrency()).longValueExact();
            if(!Objects.equals(total,usage.getAmountMinor())) throw BizException.of(ErrorCode.CONFLICT);
            rows=facts.money(ctx.tenantNo(),entity,usage.getOperationNo(),usage.getCurrency(),after,limit+1)
                    .stream().map(r -> new Attribution(r.get("activity_no").toString(),
                            new BigDecimal(r.get("amount_minor").toString()).longValueExact(),null)).toList();
            if(rows.size()>limit) { rows=rows.subList(0,limit); next=rows.getLast().activityNo(); }
        } else {
            rows=rights(ctx,usage,asset,after);
        }
        return new UsageEffects(ctx.tenantNo(),no,usage.getUserNo(),entity,usage.getStoreNo(),usage.getAssetKind(),
                asset,usage.getCurrency(),usage.getAmountMinor(),usage.getUnits(),
                usage.getOccurredAt().toInstant(ZoneOffset.UTC).toEpochMilli(),rows,next);
    }
    private List<Attribution> rights(EntitlementContext ctx,EntUsage usage,String instance,String after) {
        var asset=instances.selectOne(Wrappers.<EntInstance>lambdaQuery().eq(EntInstance::getTenantNo,ctx.tenantNo())
                .eq(EntInstance::getInstanceNo,instance).eq(EntInstance::getEntityNo,usage.getEntityNo()));
        if(asset==null) throw BizException.of(ErrorCode.CONFLICT);
        var grant=grants.find(ctx.tenantNo(),asset.getGrantNo());
        if(grant==null || grant.getPreparedSnapshot()==null) return List.of();
        if(!usage.getEntityNo().equals(grant.getEntityNo()) || !usage.getUserNo().equals(grant.getUserNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var prepared=json.readValue(grant.getPreparedSnapshot(),PreparedGrant.class);
        var permit=prepared.command().permit();
        if(permit==null || permit.activityNo().compareTo(after)<=0) return List.of();
        return List.of(new Attribution(permit.activityNo(),usage.getAmountMinor(),usage.getUnits().longValue()));
    }
}
