package ai.neargo.shop.print.api;

import java.time.Instant;

public record PrinterProbeResult(PrinterHealthStatus health, Instant observedAt, String reason) {}
