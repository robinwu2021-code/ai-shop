package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Frozen interpretation and audit identity; never a permanent original-owner authorization receipt. */
public record PublicationMarketContext(
        @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion,
        @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo,
        @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String market,
        @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String currency,
        @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer currencyScale,
        @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String contextHash,
        @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String marketReceiptNo) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown publication market context field");
    }
}
