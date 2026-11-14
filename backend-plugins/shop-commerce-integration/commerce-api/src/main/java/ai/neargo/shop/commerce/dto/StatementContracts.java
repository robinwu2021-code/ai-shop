package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Breakdown;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Public statement evidence excludes credential references, original supplier payloads and local
 * payment claims.
 */
public final class StatementContracts {
    private StatementContracts() {}

    public record Import(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String statementRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String documentHash) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown statement import field");
        }
    }

    public record Imported(String statementNo, String operationNo, String status) {}

    public record Query(
            Instant from, Instant to, String currency, String status, int page, int size) {}

    public record LineQuery(
            String statementNo, String connectionNo, String differenceCode, int page, int size) {}

    public record Statement(
            String statementNo,
            String accountNo,
            String statementRef,
            String sourceRevision,
            String documentHash,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            String status,
            String operationNo,
            String originalReceiptRef,
            Breakdown totals) {}

    public record Line(
            String lineNo,
            String statementNo,
            String storeNo,
            String connectionNo,
            String lineRef,
            String kind,
            String externalOrderRef,
            String externalAfterSaleRef,
            String currency,
            String differenceCode,
            Breakdown amounts) {}
}
