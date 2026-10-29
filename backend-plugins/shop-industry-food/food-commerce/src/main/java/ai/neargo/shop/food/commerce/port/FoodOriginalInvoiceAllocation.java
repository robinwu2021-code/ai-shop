package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.common.pricing.PricingFeeAllocation;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates supplier invoice amounts; historical published prices are weights, never a replacement
 * invoice.
 */
public final class FoodOriginalInvoiceAllocation {
    private FoodOriginalInvoiceAllocation() {}

    public record Amount(long gross, long discount, long tax, long net, String basisRef) {}

    /** The original discount and tax are distributed exactly once using stable component order. */
    public static List<Amount> allocate(
            ChannelOrderGateway.Line invoice,
            List<Long> publishedWeights,
            String publicationReceipt,
            String documentHash) {
        require(
                invoice != null
                        && publishedWeights != null
                        && !publishedWeights.isEmpty()
                        && publishedWeights.stream().allMatch(w -> w != null && w >= 0)
                        && publicationReceipt != null
                        && !publicationReceipt.isBlank()
                        && documentHash != null
                        && documentHash.matches("[a-f0-9]{64}"));
        require(
                invoice.grossExTaxMinor() >= 0
                        && invoice.merchantDiscountExTaxMinor() >= 0
                        && invoice.merchantDiscountExTaxMinor() <= invoice.grossExTaxMinor()
                        && invoice.taxMinor() >= 0
                        && Math.addExact(
                                        Math.subtractExact(
                                                invoice.grossExTaxMinor(),
                                                invoice.merchantDiscountExTaxMinor()),
                                        invoice.taxMinor())
                                == invoice.netMinor());
        long total = publishedWeights.stream().reduce(0L, Math::addExact);
        // A free historical catalog cannot explain a charged original invoice.
        require(total > 0 || invoice.netMinor() == 0 && invoice.grossExTaxMinor() == 0);
        var gross = PricingFeeAllocation.allocate(invoice.grossExTaxMinor(), publishedWeights);
        var discount = PricingFeeAllocation.allocate(invoice.merchantDiscountExTaxMinor(), gross);
        var taxable = new ArrayList<Long>();
        for (int i = 0; i < gross.size(); i++) taxable.add(gross.get(i) - discount.get(i));
        require(invoice.taxMinor() == 0 || taxable.stream().anyMatch(v -> v > 0));
        var tax = PricingFeeAllocation.allocate(invoice.taxMinor(), taxable);
        String basis =
                PricingSnapshotHash.of(
                        List.of(
                                "FOOD_ORIGINAL_INVOICE_LARGEST_REMAINDER_V1",
                                documentHash,
                                publicationReceipt,
                                invoice,
                                publishedWeights));
        var result = new ArrayList<Amount>();
        for (int i = 0; i < gross.size(); i++)
            result.add(
                    new Amount(
                            gross.get(i),
                            discount.get(i),
                            tax.get(i),
                            Math.addExact(taxable.get(i), tax.get(i)),
                            basis));
        require(
                result.stream().mapToLong(Amount::gross).reduce(0, Math::addExact)
                                == invoice.grossExTaxMinor()
                        && result.stream().mapToLong(Amount::discount).reduce(0, Math::addExact)
                                == invoice.merchantDiscountExTaxMinor()
                        && result.stream().mapToLong(Amount::tax).reduce(0, Math::addExact)
                                == invoice.taxMinor()
                        && result.stream().mapToLong(Amount::net).reduce(0, Math::addExact)
                                == invoice.netMinor());
        return List.copyOf(result);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid original FOOD invoice allocation");
    }
}
