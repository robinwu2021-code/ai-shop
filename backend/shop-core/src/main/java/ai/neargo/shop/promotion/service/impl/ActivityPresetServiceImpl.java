package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityPresetViews.PresetView;
import ai.neargo.shop.promotion.mapper.PmtPresetTemplateMapper;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.promotion.service.ActivityPresetService;
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

    public ActivityPresetServiceImpl(PmtPresetTemplateMapper presets, PromotionFeatureGate features,
                                      ObjectMapper json) {
        this.presets = presets;
        this.features = features;
        this.json = json;
    }

    @Override
    public PageData<PresetView> list(String tenantNo, String catalogKind, String scene, String category,
                                     String industryTag, long page, long size) {
        if (tenantNo == null || tenantNo.isBlank() || tenantNo.length() > 32
                || !Set.of("ACTIVITY", "PAID_PRODUCT").contains(catalogKind)
                || page < 1 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<PresetView> result = new ArrayList<>();
        Set<String> latest = new HashSet<>();
        for (var row : presets.catalog(tenantNo, catalogKind)) {
            if (!latest.add(row.getTemplateCode())) continue;
            JsonNode schema = json.readTree(row.getDraftSchema());
            String actualScene = schema.path("scene").asText("PAID_PRODUCT");
            if ("COUPON_DISTRIBUTION".equals(actualScene)) actualScene = "ENTITLEMENT_GRANT";
            List<String> industries = strings(json.readTree(row.getIndustryTags()));
            if (scene != null && !scene.equals(actualScene)) continue;
            if (category != null && !category.equals(row.getCategory())) continue;
            if (industryTag != null && !industries.contains(industryTag) && !industries.contains("通用")) continue;
            var assessment = features.assessPreset(strings(json.readTree(row.getRequiredCapabilities())), Set.of());
            boolean enabled = Integer.valueOf(1).equals(row.getEnabled());
            result.add(new PresetView(row.getTemplateCode(), row.getTemplateVersion(), row.getCatalogKind(),
                    row.getName(), row.getCategory(), actualScene, industries, enabled,
                    enabled && assessment.available(), assessment.missingCapabilities(), schema));
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
