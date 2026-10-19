package ai.neargo.shop.event.outbox;

/** Explicit routing avoids deriving ordering from timestamps or proxy class names. */
public record PublishOptions(String streamKey, int schemaVersion) {
    public PublishOptions {
        if (streamKey == null || streamKey.isBlank() || streamKey.length() > 128 || schemaVersion < 1) {
            throw new IllegalArgumentException("Invalid publish options");
        }
    }
}
