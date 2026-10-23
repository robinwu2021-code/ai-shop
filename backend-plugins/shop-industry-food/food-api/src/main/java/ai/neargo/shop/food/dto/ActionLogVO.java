package ai.neargo.shop.food.dto;

import java.time.Instant;

/** Dining reception contract; monetary authority remains outside this resource. */
public record ActionLogVO(String actionNo, String action, String actorRef, Instant occurredAt, String reason) {}
