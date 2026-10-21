package ai.neargo.shop.merchant.dto;

/** The returned revision belongs to the resource root, not an independent rule-level concurrency token. */
public record ScheduleRuleVO(String ruleNo, String resourceNo, long resourceRevision, String status) { }
