package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotNull;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.OffsetDateTime;

/**
 * Original publication identity only; these facts cannot authorize prices, inventory or order
 * acceptance.
 */
public final class MenuSourceContracts {
    private MenuSourceContracts() {}

    public enum Kind {
        MENU,
        CATEGORY,
        OFFER,
        SKU,
        OPTION_GROUP,
        OPTION,
        BUNDLE_GROUP,
        BUNDLE_MEMBER
    }

    public enum Mode {
        CURRENT,
        HISTORICAL
    }

    /**
     * Explicit parent membership prevents a valid option identity from authorizing a different menu
     * selection.
     */
    public record Query(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo,
            @NotNull @JsonDeserialize(using = KindDeserializer.class) Kind kind,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String resourceRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String salesOfferRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String optionGroupRef,
            @NotNull @JsonDeserialize(using = ModeDeserializer.class) Mode mode,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String variantSkuRef,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String bundleGroupRef) {
        public Query(
                String entityNo,
                String storeNo,
                String menuNo,
                String revisionNo,
                Kind kind,
                String resourceRef,
                String salesOfferRef,
                String optionGroupRef,
                Mode mode) {
            this(
                    entityNo,
                    storeNo,
                    menuNo,
                    revisionNo,
                    kind,
                    resourceRef,
                    salesOfferRef,
                    optionGroupRef,
                    mode,
                    null,
                    null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown menu source query field");
        }
    }

    public record Resource(
            @JsonDeserialize(using = KindDeserializer.class) Kind kind,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String resourceRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String salesOfferRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String optionGroupRef,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String variantSkuRef,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String bundleGroupRef) {
        public Resource(
                Kind kind, String resourceRef, String salesOfferRef, String optionGroupRef) {
            this(kind, resourceRef, salesOfferRef, optionGroupRef, null, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown menu resource field");
        }
    }

    public static final class KindDeserializer extends ValueDeserializer<Kind> {
        @Override
        public Kind deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(
                        Kind.class, "Menu resource kind must be a string");
            try {
                return Kind.valueOf(parser.getString());
            } catch (IllegalArgumentException failure) {
                return context.reportInputMismatch(Kind.class, "Unknown menu resource kind");
            }
        }
    }

    public static final class ModeDeserializer extends ValueDeserializer<Mode> {
        @Override
        public Mode deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(Mode.class, "Menu source mode must be a string");
            try {
                return Mode.valueOf(parser.getString());
            } catch (IllegalArgumentException failure) {
                return context.reportInputMismatch(Mode.class, "Unknown menu source mode");
            }
        }
    }

    /**
     * Includes original bytes so remote readers verify scope/hash and resource membership instead
     * of trusting a flag.
     */
    public record Proof(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion,
            Query query,
            ConfigRevisionContentVO publication,
            Resource resource,
            OffsetDateTime verifiedAt,
            OffsetDateTime validUntil,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown menu source proof field");
        }
    }
}
