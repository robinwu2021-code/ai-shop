package ai.neargo.shop.spi.report;

import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Scope;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Resolves the original persisted kitchen event day without trusting transport timestamps. */
public interface ReportKitchenEventDayPort {
    record Change(Scope scope, String eventNo, String taskNo, String action, BigDecimal quantity) {
        public Change {
            Objects.requireNonNull(scope);
            ReportContracts.identifier(eventNo, 64);
            ReportContracts.identifier(taskNo, 64);
            ReportContracts.identifier(action, 48);
            if (quantity == null || quantity.signum() <= 0)
                throw new IllegalArgumentException("REPORT_INVALID_KITCHEN_EVENT");
        }
    }

    record AffectedDay(
            Change original, LocalDate businessDate, DayEvidence day, Instant originalOccurredAt) {
        public AffectedDay {
            Objects.requireNonNull(original);
            Objects.requireNonNull(businessDate);
            Objects.requireNonNull(day);
            Objects.requireNonNull(originalOccurredAt);
            if (originalOccurredAt.isBefore(day.startsAt())
                    || !originalOccurredAt.isBefore(day.endsAt()))
                throw new IllegalArgumentException("REPORT_INVALID_KITCHEN_EVENT_DAY");
        }
    }

    AffectedDay affectedDay(Change change);
}
