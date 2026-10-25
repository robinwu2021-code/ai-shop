package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;
import ai.neargo.shop.printbridge.PrintActorFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Profile("api")
@ai.neargo.shop.portal.PrintEndpoint
@RestController
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class BizPrintAgentController {
    private final PrintAgentService service0;
    private final PrintActorFactory actors;

    public BizPrintAgentController(PrintAgentService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @GetMapping("/biz/print/agents")
    public PageData<AgentVO> listAgents(@ModelAttribute ListAgentsQuery query) {
        var actor = actors.merchant(null);
        return service0.listAgents(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PostMapping("/biz/print/agents")
    public PrintOperation createAgent(
            @RequestBody CreateAgentReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.createAgent(request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @GetMapping("/biz/print/agents/{agentNo}")
    public AgentDetailVO getAgent(@PathVariable("agentNo") String agentNo) {
        var actor = actors.merchant(null);
        return service0.getAgent(agentNo, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PostMapping("/biz/print/agents/{agentNo}/enrollments")
    public PrintOperation issueEnrollment(
            @PathVariable("agentNo") String agentNo,
            @RequestBody IssueEnrollmentReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.issueEnrollment(agentNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/agents/{agentNo}/revocations")
    public PrintOperation revokeAgent(
            @PathVariable("agentNo") String agentNo,
            @RequestBody RevokeAgentReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.revokeAgent(agentNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/agents/{agentNo}/credential-rotations")
    public PrintOperation rotateAgentCredential(
            @PathVariable("agentNo") String agentNo,
            @RequestBody RotateAgentCredentialReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.rotateAgentCredential(agentNo, request, actor);
    }
}
