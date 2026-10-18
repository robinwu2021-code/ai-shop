package ai.neargo.shop.promotion.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityPresetViews.PresetView;

/** One directory serves merchant marketing and card-product setup, while each executor keeps its own rules. */
public interface ActivityPresetService {
    PageData<PresetView> list(String tenantNo, String catalogKind, String scene, String category,
                              String industryTag, long page, long size);
}
