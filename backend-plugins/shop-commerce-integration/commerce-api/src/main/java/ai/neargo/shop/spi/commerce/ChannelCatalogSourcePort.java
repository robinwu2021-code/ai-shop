package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 原行业拥有完整目录、价格/税/套餐/配料及营业时段事实；渠道不重新定价或自报库存。 */
public interface ChannelCatalogSourcePort {
    Owner owner();

    Document capture(CaptureCommand command);

    record ValidatedSource(
            Owner owner,
            String sourceRef,
            String selectionRevision,
            int priority,
            Validation proof) {}

    record CaptureCommand(
            String entityNo,
            String storeNo,
            Owner owner,
            String connectionNo,
            String connectionRevisionNo,
            List<ValidatedSource> sources) {}

    record Document(
            Owner owner,
            String currency,
            List<ValidatedSource> sources,
            List<Section> sections,
            List<Offer> offers,
            List<Schedule> schedules,
            Instant validUntil,
            String receiptNo) {}

    record Section(String key, String name, int sortNo) {}

    record Offer(
            String offerRef,
            String sourceRef,
            String selectionRevision,
            String name,
            String sectionKey,
            List<Sku> skus,
            List<OptionGroup> optionGroups,
            List<BundleGroup> bundleGroups,
            List<Image> images) {}

    record Sku(String skuRef, String name, Price price, String inventoryPolicyRef) {}

    record Price(
            long amountMinor,
            String currency,
            String priceSourceRef,
            String priceRevision,
            String taxBasisRef,
            String receiptNo) {}

    record OptionGroup(
            String groupRef, String name, int min, int max, List<OptionChoice> choices) {}

    record OptionChoice(
            String choiceRef,
            String name,
            Price price,
            String inventoryPolicyRef,
            int min,
            int max) {}

    record BundleGroup(
            String groupRef, String name, int min, int max, List<BundleChoice> choices) {}

    record BundleChoice(String choiceRef, String offerRef, Price extraPrice, int quantity) {}

    record Image(String assetNo, String url, String receiptNo) {}

    record Schedule(
            String sourceRef,
            String selectionRevision,
            String timezone,
            List<Integer> weekdays,
            LocalTime from,
            LocalTime to,
            LocalDate validFrom,
            LocalDate validTo) {}
}
