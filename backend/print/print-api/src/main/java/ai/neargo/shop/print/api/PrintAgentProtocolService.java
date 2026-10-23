package ai.neargo.shop.print.api;

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

public interface PrintAgentProtocolService {
    DeviceEnrollVO enroll(DeviceEnrollReq request);

    DeviceOpenSessionVO openSession(DeviceOpenSessionReq request, PrintAgentIdentity identity);

    DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request, PrintAgentIdentity identity);

    DeviceClaimVO claim(DeviceClaimReq request, PrintAgentIdentity identity);

    DeviceAcceptDispatchVO acceptDispatch(
            String dispatchKey, DeviceAcceptDispatchReq request, PrintAgentIdentity identity);

    DeviceRenewLeaseVO renewLease(
            String dispatchKey, DeviceRenewLeaseReq request, PrintAgentIdentity identity);

    DeviceReportEventsVO reportEvents(DeviceReportEventsReq request, PrintAgentIdentity identity);

    DeviceGetDispatchVO getDispatch(String dispatchKey, PrintAgentIdentity identity);

    DeviceRecoverVO recover(DeviceRecoverReq request, PrintAgentIdentity identity);

    DeviceReportCommandResultVO reportCommandResult(
            String commandNo, DeviceReportCommandResultReq request, PrintAgentIdentity identity);
}
