package ai.neargo.shop.print.api;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
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

public interface PrintAgentService {
    PageData<AgentVO> listAgents(ListAgentsQuery query, PrintActor actor);

    PrintOperation createAgent(CreateAgentReq request, PrintActor actor);

    AgentDetailVO getAgent(String agentNo, PrintActor actor);

    PrintOperation issueEnrollment(String agentNo, IssueEnrollmentReq request, PrintActor actor);

    PrintOperation revokeAgent(String agentNo, RevokeAgentReq request, PrintActor actor);

    PrintOperation rotateAgentCredential(
            String agentNo, RotateAgentCredentialReq request, PrintActor actor);
}
