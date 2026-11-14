package ai.neargo.shop.promotion.dto;

import java.time.Instant;

/** Management views contain the actual persisted revision; physical database IDs never leave core. */
public final class UnifiedActivityViews {
    private UnifiedActivityViews() { }
    public record SaveDraft(String activityNo, Long expectedRevision, UnifiedActivityDefinition definition) { }
    public record ActivityView(String activityNo, long revision, String status, String currentVersionNo,
                               UnifiedActivityDefinition definition, String documentNo) {
    public ActivityView(String activityNo, long revision, String status, String currentVersionNo, UnifiedActivityDefinition definition) {
        this(activityNo, revision, status, currentVersionNo, definition, null);
    }
    public ActivityView withDocumentNo(String number) {
        return new ActivityView(activityNo, revision, status, currentVersionNo, definition, number);
    }
 }
    public record VersionView(String activityNo, String versionNo, int versionIndex, String snapshotHash,
                              Instant publishedAt, UnifiedActivityDefinition definition) { }
}
