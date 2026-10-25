package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintAgentDtos {
    private PrintAgentDtos() {}

    public record ListAgentsQuery(String storeNo, String status, Integer page, Integer size) {}

    public record AgentVO(
            String agentNo,
            String storeNo,
            String name,
            String platform,
            String status,
            long sessionEpoch,
            int credentialVersion,
            Instant lastSeenAt,
            long revision) {}

    public record CreateAgentReq(String storeNo, String name, String platform) {}

    public record AgentDetailVO(
            AgentVO agent, List<String> printerNos, List<DiscoveredPrinter> discoveredPrinters) {
        public AgentDetailVO {
            printerNos = List.copyOf(Objects.requireNonNull(printerNos, "printerNos"));
        }
    }

    public record IssueEnrollmentReq(Long expectedRevision, String clientPublicKey) {}

    public record RevokeAgentReq(Long expectedRevision, String reason) {}

    public record RotateAgentCredentialReq(
            Long expectedRevision, String clientPublicKey, String reason) {}
}
