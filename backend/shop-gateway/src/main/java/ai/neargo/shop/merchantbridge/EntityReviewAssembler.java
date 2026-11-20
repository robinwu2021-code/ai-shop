package ai.neargo.shop.merchantbridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.EntityProfileRemoteCalls;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import ai.neargo.shop.payclient.PayEntityProfileApi.SummaryQuery;
import ai.neargo.shop.platform.OpsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Adds application review only after the entity service has established owner scope. */
@Component
public class EntityReviewAssembler {
    private final OpsService ops;
    private final QualificationMapper qualifications;
    private PayEntityProfileApi profiles;
    private StoreSyncProperties properties;
    @Autowired(required=false)
    public void setEntityProfiles(PayEntityProfileApi profiles,StoreSyncProperties properties){this.profiles=profiles;this.properties=properties;}

    public EntityReviewAssembler(OpsService ops, QualificationMapper qualifications) {
        this.ops = ops;
        this.qualifications = qualifications;
    }

    public List<EntityVO> enrich(String userNo, List<EntityVO> entities) {
        if (entities.isEmpty()) return entities;
        var applies = ops.latestAppliesOf(userNo, entities.stream().map(EntityVO::entityNo).toList());
        var approved = entities.stream().filter(e -> !"PENDING_LICENSE".equals(e.status()))
                .map(EntityVO::entityNo).toList();
        var numbers = new LinkedHashMap<String, String>();
        if (!approved.isEmpty()) {
            var rows = DataScopeContext.executeWithoutScope(() -> qualifications.selectList(
                    Wrappers.<MchQualification>lambdaQuery().in(MchQualification::getEntityNo, approved)
                            // A natural person's qualification is their ID card, held only masked.
                            .in(MchQualification::getQualType, MchQualification.BUSINESS_LICENSE, "ID_CARD")
                            .orderByDesc(MchQualification::getId)));
            for (var row : rows) numbers.putIfAbsent(row.getEntityNo(), row.getQualNumber());
        }
        var summaries=profiles==null?Map.<String,Map<String,Object>>of():
            EntityProfileRemoteCalls.call(()->profiles.summaries(new SummaryQuery(properties.sourceScope(),entities.stream().map(EntityVO::entityNo).toList())));
        if(summaries==null)throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        return entities.stream().map(e -> {
            var a = applies.get(e.entityNo());
            boolean pending = "PENDING_LICENSE".equals(e.status());
            String review = !pending ? "APPROVED" : a == null ? "NONE" : switch (a.status()) {
                case "PENDING", "REVIEWING" -> "REVIEWING";
                case "REJECTED" -> "REJECTED";
                default -> "APPROVED";
            };
            String tax = pending && a != null ? a.qualificationItems().stream()
                    .filter(q -> "BUSINESS_LICENSE".equals(q.type())).map(q -> q.code() == null ? "" : q.code())
                    .findFirst().orElse("") : numbers.getOrDefault(e.entityNo(), "");
            var enriched=e.withReview(review, a == null ? null : a.applyNo(),
                    "REJECTED".equals(review) ? a.rejectReason() : "", tax, a == null ? "" : a.contactName());
            var profile=summaries.get(e.entityNo());
            return profile==null?enriched:enriched.withProfile((String)profile.get("platformReview"),
                profile.get("version") instanceof Number n?n.longValue():null,
                profile.get("approvedVersion") instanceof Number n?n.longValue():null,(String)profile.get("merchantNo"));
        }).toList();
    }
}
