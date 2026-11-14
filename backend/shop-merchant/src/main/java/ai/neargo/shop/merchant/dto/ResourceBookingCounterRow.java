package ai.neargo.shop.merchant.dto;

/** Aggregate active-unit evidence is read under resource roots before trusting mutable bucket counters. */
public record ResourceBookingCounterRow(long held, long confirmed, long invalid) {
}
