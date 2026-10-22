package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;

/**
 * Restaurant display overrides only; empty imageRefs keeps the authoritative product cover.
 * Image references are at most 20 unique canonical assetKey values returned by the original uploader;
 * the media authority must confirm active public GOODS assets owned by the same entity and store.
 */
public record DishPresentation(String displayName, List<LocalizedName> localizedNames, List<String> imageRefs,
        List<String> tags, List<String> allergenNotes) {
    public DishPresentation {
        localizedNames = localizedNames == null ? List.of() : List.copyOf(localizedNames);
        imageRefs = imageRefs == null ? List.of() : List.copyOf(imageRefs);
        tags = tags == null ? List.of() : List.copyOf(tags);
        allergenNotes = allergenNotes == null ? List.of() : List.copyOf(allergenNotes);
    }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown display field"); }

    /** A locale identifier and its display name; this is not a second product title authority. */
    public record LocalizedName(String locale, String name) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown localized name field");
        }
    }
}
