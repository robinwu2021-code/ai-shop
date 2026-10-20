package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.policy.ActivityPresetExecutionPolicy;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityPresetViews.PresetView;
import ai.neargo.shop.promotion.mapper.PmtPresetTemplateMapper;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.promotion.service.ActivityPresetService;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import org.springframework.beans.factory.ObjectProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Seeded recommendations remain unavailable until execution owners have supplied verified readiness. */
@Service
public class ActivityPresetServiceImpl implements ActivityPresetService {
    private final PmtPresetTemplateMapper presets;
    private final PromotionFeatureGate features;
    private final ObjectMapper json;
    private final ObjectProvider<ActivityCapabilityEvidence> evidence;

    public ActivityPresetServiceImpl(PmtPresetTemplateMapper presets, PromotionFeatureGate features,
                                      ObjectMapper json, ObjectProvider<ActivityCapabilityEvidence> evidence) {
        this.presets = presets;
        this.features = features;
        this.json = json;
        this.evidence = evidence;
    }

    @Override
    public PageData<PresetView> list(ActivityActor actor, String catalogKind, String scene, String category,
                                     String industryTag, long page, long size) {
        if (actor == null
                || !Set.of("ACTIVITY", "PAID_PRODUCT").contains(catalogKind)
                || page < 1 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Set<ActivityCapability> ready = new HashSet<>();
        evidence.orderedStream().forEach(provider -> ready.addAll(provider.verifiedFor(actor)));
        List<PresetView> result = new ArrayList<>();
        Set<String> latest = new HashSet<>();
        for (var row : presets.catalog(actor.tenantNo(), catalogKind)) {
            if (!latest.add(row.getTemplateCode())) continue;
            JsonNode schema = json.readTree(row.getDraftSchema());
            String actualScene = schema.path("scene").asText("PAID_PRODUCT");
            if ("COUPON_DISTRIBUTION".equals(actualScene)) actualScene = "ENTITLEMENT_GRANT";
            List<String> industries = strings(json.readTree(row.getIndustryTags()));
            if (scene != null && !scene.equals(actualScene)) continue;
            if (category != null && !category.equals(row.getCategory())) continue;
            if (industryTag != null && !industries.contains(industryTag) && !industries.contains("通用")) continue;
            var assessment = features.assessPreset(strings(json.readTree(row.getRequiredCapabilities())), ready);
            boolean enabled = Integer.valueOf(1).equals(row.getEnabled());
            List<String> missing = new ArrayList<>(assessment.missingCapabilities());
            missing.addAll(new ActivityPresetExecutionPolicy()
                    .missing(actor, row.getTemplateCode()));
            if (!enabled) missing.add("PRESET_DISABLED");
            if (!assessment.available() && assessment.missingCapabilities().isEmpty()) missing.add("PRESETS_DISABLED");
            result.add(new PresetView(row.getTemplateCode(), row.getTemplateVersion(), row.getCatalogKind(),
                    row.getName(), row.getCategory(), actualScene, industries, enabled,
                    enabled && assessment.available() && missing.isEmpty(), List.copyOf(missing), schema));
        }
        int from = (int) Math.min((page - 1) * size, result.size());
        int to = (int) Math.min(from + size, result.size());
        return PageData.of(List.copyOf(result.subList(from, to)), result.size(), page, size);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }
}
