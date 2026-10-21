package ai.neargo.shop.merchant.dto;

/** A single joined projection retains the original owner identity and command result, not the moving head. */
public record ResourceCommandFactRow(String tenantNo, String entityNo, String storeNo, String commandNo,
        String sourceType, String sourceNo, long sourceRevision, String action, String commandHash,
        String frozenPayload, String status, long revision, String resultPayload, String rejectionCode) { }
