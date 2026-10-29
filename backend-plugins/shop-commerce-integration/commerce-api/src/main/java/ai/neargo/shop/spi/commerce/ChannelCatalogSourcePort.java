package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceIntegerDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.CommerceWireCodecs;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 原行业拥有完整目录、价格/税/套餐/配料及营业时段事实；渠道不重新定价或自报库存。 */
public interface ChannelCatalogSourcePort {
    Owner owner();

    Document capture(CaptureCommand command);

    record ValidatedSource(
            @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String selectionRevision,
            int priority,
            Validation proof) {}

    record CaptureCommand(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionRevisionNo,
            List<ValidatedSource> sources,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    List<ChannelSourceWithdrawalProvider.Facts> withdrawals) {
        public CaptureCommand(
                String entityNo,
                String storeNo,
                Owner owner,
                String connectionNo,
                String connectionRevisionNo,
                List<ValidatedSource> sources) {
            this(entityNo, storeNo, owner, connectionNo, connectionRevisionNo, sources, null);
        }

        public CaptureCommand {
            sources = sources == null ? null : List.copyOf(sources);
            withdrawals = withdrawals == null ? null : List.copyOf(withdrawals);
        }
    }

    record Document(
            @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
            List<ValidatedSource> sources,
            List<Section> sections,
            List<Offer> offers,
            List<Schedule> schedules,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String receiptNo,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = CommerceIntegerDeserializer.class)
                    Integer schemaVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) ChannelCatalogDetails details,
            @JsonInclude(JsonInclude.Include.NON_NULL) CatalogAvailability availability,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<CatalogTaxBasis> taxBases,
            @JsonInclude(JsonInclude.Include.NON_NULL) CatalogReplacement replacement) {
        public Document {
            taxBases = taxBases == null ? null : List.copyOf(taxBases);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog document field");
        }

        /** Existing v3 source implementations retain their exact serialized shape and checksum. */
        public Document(
                Owner owner,
                String currency,
                List<ValidatedSource> sources,
                List<Section> sections,
                List<Offer> offers,
                List<Schedule> schedules,
                Instant validUntil,
                String receiptNo,
                Integer schemaVersion,
                ChannelCatalogDetails details,
                CatalogAvailability availability,
                List<CatalogTaxBasis> taxBases) {
            this(
                    owner,
                    currency,
                    sources,
                    sections,
                    offers,
                    schedules,
                    validUntil,
                    receiptNo,
                    schemaVersion,
                    details,
                    availability,
                    taxBases,
                    null);
        }

        /**
         * Version-two callers retain the exact serialized shape and cannot implicitly opt into
         * version three.
         */
        public Document(
                Owner owner,
                String currency,
                List<ValidatedSource> sources,
                List<Section> sections,
                List<Offer> offers,
                List<Schedule> schedules,
                Instant validUntil,
                String receiptNo,
                Integer schemaVersion,
                ChannelCatalogDetails details) {
            this(
                    owner,
                    currency,
                    sources,
                    sections,
                    offers,
                    schedules,
                    validUntil,
                    receiptNo,
                    schemaVersion,
                    details,
                    null,
                    null);
        }

        /** Original callers and persisted payloads retain their version-one shape and hashing. */
        public Document(
                @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
                List<ValidatedSource> sources,
                List<Section> sections,
                List<Offer> offers,
                List<Schedule> schedules,
                @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String receiptNo) {
            this(
                    owner,
                    currency,
                    sources,
                    sections,
                    offers,
                    schedules,
                    validUntil,
                    receiptNo,
                    null,
                    null);
        }
    }

    record Section(
            String key,
            String name,
            int sortNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) SectionSource source) {
        public Section(String key, String name, int sortNo) {
            this(key, name, sortNo, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog section field");
        }
    }

    record SectionSource(String sourceRef, String selectionRevision, String categoryRef) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown section source field");
        }
    }

    record Offer(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String offerRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String selectionRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String name,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sectionKey,
            List<Sku> skus,
            List<OptionGroup> optionGroups,
            List<BundleGroup> bundleGroups,
            List<Image> images) {}

    record Sku(String skuRef, String name, Price price, String inventoryPolicyRef) {}

    record Price(
            long amountMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String priceSourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String priceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String taxBasisRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String receiptNo) {}

    record OptionGroup(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String groupRef,
            String name,
            int min,
            int max,
            List<OptionChoice> choices) {}

    record OptionChoice(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String choiceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String name,
            Price price,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String inventoryPolicyRef,
            int min,
            int max) {}

    record BundleGroup(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String groupRef,
            String name,
            int min,
            int max,
            List<BundleChoice> choices) {}

    record BundleChoice(String choiceRef, String offerRef, Price extraPrice, int quantity) {}

    /** Stable media revision participates in v3 content; old constructors omit it byte-for-byte. */
    record Image(
            String assetNo,
            String url,
            String receiptNo,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String sourceRevision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog image field");
        }

        public Image(String assetNo, String url, String receiptNo) {
            this(assetNo, url, receiptNo, null);
        }
    }

    record Schedule(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String selectionRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String timezone,
            List<Integer> weekdays,
            LocalTime from,
            LocalTime to,
            LocalDate validFrom,
            LocalDate validTo) {}
}
