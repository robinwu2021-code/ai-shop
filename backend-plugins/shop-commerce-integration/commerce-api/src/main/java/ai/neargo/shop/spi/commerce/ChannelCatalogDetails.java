package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceIntegerDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Image;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Section;
import ai.neargo.shop.spi.product.DishOfferFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Version two is lossless for SKU-specific rules, decimal picks and local-date exceptions; never a
 * legacy fallback.
 */
public record ChannelCatalogDetails(
        List<Section> sections, List<Offer> offers, List<SourceSchedule> schedules) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw unknown();
    }

    public record Offer(
            String offerRef,
            String sourceRef,
            String selectionRevision,
            String goodsRef,
            String name,
            String sectionKey,
            List<Variant> variants,
            List<Image> images,
            @JsonInclude(JsonInclude.Include.NON_NULL) String originalOfferRef,
            @JsonInclude(JsonInclude.Include.NON_NULL) Presentation presentation,
            @JsonInclude(JsonInclude.Include.NON_NULL) Measurement measurement) {
        public Offer(
                String offerRef,
                String sourceRef,
                String selectionRevision,
                String goodsRef,
                String name,
                String sectionKey,
                List<Variant> variants,
                List<Image> images,
                String originalOfferRef) {
            this(
                    offerRef,
                    sourceRef,
                    selectionRevision,
                    goodsRef,
                    name,
                    sectionKey,
                    variants,
                    images,
                    originalOfferRef,
                    null,
                    null);
        }

        /**
         * Legacy JSON and constructor remain unchanged; v3 can preserve the original sale identity
         * explicitly.
         */
        public Offer(
                String offerRef,
                String sourceRef,
                String selectionRevision,
                String goodsRef,
                String name,
                String sectionKey,
                List<Variant> variants,
                List<Image> images) {
            this(
                    offerRef,
                    sourceRef,
                    selectionRevision,
                    goodsRef,
                    name,
                    sectionKey,
                    variants,
                    images,
                    null,
                    null,
                    null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    /**
     * Localized names and allergen text remain original merchandising facts, usable by every
     * industry.
     */
    public record Presentation(
            List<LocalizedName> localizedNames,
            List<String> tags,
            List<String> allergenNotes,
            @JsonDeserialize(using = CommerceIntegerDeserializer.class)
                    Integer preparationSeconds) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record LocalizedName(String locale, String name) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    /**
     * Physical quantity rules cannot be inferred from an integer billing quantity or a SKU name.
     */
    public record Measurement(
            String unitCode,
            String mode,
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer precision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String allowedDeviation) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record Variant(
            String skuRef,
            String name,
            UnitPrice price,
            Inventory inventory,
            List<OptionGroup> optionGroups,
            List<BundleGroup> bundleGroups) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record UnitPrice(
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor,
            String currency,
            String priceSourceRef,
            String priceRevision,
            String taxBasisRef,
            String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    /**
     * UNKNOWN has neither policy nor receipt; absence cannot be interpreted as zero sellable stock.
     */
    public record Inventory(String status, String policyRef, String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record QuantityRange(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String minimum,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String maximum,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String defaultQuantity) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record OptionGroup(
            String groupRef,
            String name,
            QuantityRange quantity,
            List<Option> choices,
            SelectionRules rules) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record Option(
            String choiceRef,
            String name,
            String sourceKind,
            String sourceRef,
            String skuRef,
            UnitPrice price,
            Inventory inventory,
            QuantityRange quantity) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record SelectionRules(List<List<String>> mutexSets, List<Dependency> requires) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record Dependency(String choiceRef, List<String> requiresRefs) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record BundleGroup(
            String groupRef,
            String name,
            QuantityRange quantity,
            List<BundleMember> choices,
            @JsonInclude(JsonInclude.Include.NON_NULL) String baseSkuRef) {
        public BundleGroup(
                String groupRef, String name, QuantityRange quantity, List<BundleMember> choices) {
            this(groupRef, name, quantity, choices, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record BundleMember(
            String choiceRef,
            String name,
            String skuRef,
            String priceMode,
            UnitPrice extraPrice,
            Inventory inventory,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String unitsPerPick,
            QuantityRange quantity) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    /**
     * Source windows are local start-date rules, not grants beyond the original store operating
     * calendar.
     */
    public record SourceSchedule(
            String sourceRef,
            String selectionRevision,
            String timezone,
            LocalDate validFrom,
            LocalDate validTo,
            List<WeekWindow> weekWindows,
            List<DateException> exceptions) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record WeekWindow(
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer weekday,
            LocalTime from,
            LocalTime to,
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer endDayOffset) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    public record DateException(
            LocalDate localDate,
            String mode,
            LocalTime from,
            LocalTime to,
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer endDayOffset) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw unknown();
        }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown detailed catalog field");
    }
}
