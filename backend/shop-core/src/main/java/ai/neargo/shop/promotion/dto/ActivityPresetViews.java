package ai.neargo.shop.promotion.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Preset parameters are read-only suggestions, never permission or a published activity. */
public final class ActivityPresetViews {
    private ActivityPresetViews() { }

    public record PresetView(String templateCode, int templateVersion, String catalogKind, String name,
                             String category, String scene, List<String> industryTags, boolean enabled,
                             boolean available, List<String> missingCapabilities, JsonNode draftSchema) {
        public PresetView {
            industryTags = List.copyOf(industryTags);
            missingCapabilities = List.copyOf(missingCapabilities);
            draftSchema = draftSchema.deepCopy();
        }
        @Override public JsonNode draftSchema() { return draftSchema.deepCopy(); }
    }
}
