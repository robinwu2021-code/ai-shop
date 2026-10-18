package ai.neargo.shop.trade.dto;

import java.time.Instant;

public record AuthorizationView(String authorizationRef, String status, Instant expiresAt) { }
