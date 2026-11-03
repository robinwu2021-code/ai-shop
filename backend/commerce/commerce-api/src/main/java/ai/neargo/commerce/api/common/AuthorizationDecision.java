package ai.neargo.commerce.api.common;

import java.util.Optional;

public record AuthorizationDecision(boolean allowed, String roleCode, Optional<String> denialCode) {}
