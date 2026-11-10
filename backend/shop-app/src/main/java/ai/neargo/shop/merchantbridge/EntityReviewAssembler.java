package ai.neargo.shop.merchantbridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper;
import ai.neargo.shop.platform.OpsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/** Adds application review only after the entity service has established owner scope. */
@Component
public class EntityReviewAssembler {
    private final OpsService ops;
    private final QualificationMapper qualifications;

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
                            .eq(MchQualification::getQualType, MchQualification.BUSINESS_LICENSE)
                            .orderByDesc(MchQualification::getId)));
            for (var row : rows) numbers.putIfAbsent(row.getEntityNo(), row.getQualNumber());
        }
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
            return e.withReview(review, a == null ? null : a.applyNo(),
                    "REJECTED".equals(review) ? a.rejectReason() : "", tax, a == null ? "" : a.contactName());
        }).toList();
    }
}
