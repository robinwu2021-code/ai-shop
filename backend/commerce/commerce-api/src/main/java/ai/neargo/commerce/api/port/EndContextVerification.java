package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record EndContextVerification(Optional<ai.neargo.commerce.api.access.EndContext> context, Optional<String> rejectionCode) {}
