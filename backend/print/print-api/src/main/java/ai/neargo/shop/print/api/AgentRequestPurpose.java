package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum AgentRequestPurpose {
    OPEN_SESSION,
    HEARTBEAT,
    CLAIM,
    ACKNOWLEDGE,
    RENEW_LEASE,
    REPORT_EVIDENCE,
    RECOVER,
    COMMAND_RESULT,
    READ_DISPATCH
}
