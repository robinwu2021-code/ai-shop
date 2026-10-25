package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintAgentIdentity(
        String tenantNo,
        String entityNo,
        String storeNo,
        String agentNo,
        int credentialVersion,
        long sessionEpoch) {}
