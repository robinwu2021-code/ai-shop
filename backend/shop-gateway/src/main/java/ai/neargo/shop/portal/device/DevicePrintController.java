package ai.neargo.shop.portal.device;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Profile("api")
@ai.neargo.shop.portal.PrintEndpoint
@RestController
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class DevicePrintController {
    private final PrintAgentProtocolService service0;

    public DevicePrintController(PrintAgentProtocolService service0) {
        this.service0 = service0;
    }

    @PostMapping("/device/print/v1/enrollments")
    public DeviceEnrollVO enroll(@RequestBody DeviceEnrollReq request) {
        return service0.enroll(request);
    }

    @PostMapping("/device/print/v1/sessions")
    public DeviceOpenSessionVO openSession(
            @RequestBody DeviceOpenSessionReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.openSession(request, identity);
    }

    @PostMapping("/device/print/v1/heartbeats")
    public DeviceHeartbeatVO heartbeat(
            @RequestBody DeviceHeartbeatReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.heartbeat(request, identity);
    }

    @PostMapping("/device/print/v1/claims")
    public DeviceClaimVO claim(
            @RequestBody DeviceClaimReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.claim(request, identity);
    }

    @PostMapping("/device/print/v1/dispatches/{dispatchKey}/acceptances")
    public DeviceAcceptDispatchVO acceptDispatch(
            @PathVariable("dispatchKey") String dispatchKey,
            @RequestBody DeviceAcceptDispatchReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.acceptDispatch(dispatchKey, request, identity);
    }

    @PostMapping("/device/print/v1/dispatches/{dispatchKey}/lease-renewals")
    public DeviceRenewLeaseVO renewLease(
            @PathVariable("dispatchKey") String dispatchKey,
            @RequestBody DeviceRenewLeaseReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.renewLease(dispatchKey, request, identity);
    }

    @PostMapping("/device/print/v1/events")
    public DeviceReportEventsVO reportEvents(
            @RequestBody DeviceReportEventsReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.reportEvents(request, identity);
    }

    @GetMapping("/device/print/v1/dispatches/{dispatchKey}")
    public DeviceGetDispatchVO getDispatch(
            @PathVariable("dispatchKey") String dispatchKey,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.getDispatch(dispatchKey, identity);
    }

    @PostMapping("/device/print/v1/recoveries")
    public DeviceRecoverVO recover(
            @RequestBody DeviceRecoverReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.recover(request, identity);
    }

    @PostMapping("/device/print/v1/commands/{commandNo}/results")
    public DeviceReportCommandResultVO reportCommandResult(
            @PathVariable("commandNo") String commandNo,
            @RequestBody DeviceReportCommandResultReq request,
            @RequestAttribute("printAgentIdentity") PrintAgentIdentity identity) {
        return service0.reportCommandResult(commandNo, request, identity);
    }
}
