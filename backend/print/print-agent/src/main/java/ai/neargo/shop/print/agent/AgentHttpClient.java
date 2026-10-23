package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;

public interface AgentHttpClient {
    DeviceEnrollVO enroll(DeviceEnrollReq request);

    DeviceOpenSessionVO openSession(DeviceOpenSessionReq request);

    DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request);

    DeviceClaimVO claim(DeviceClaimReq request);

    DeviceAcceptDispatchVO accept(String dispatchKey, DeviceAcceptDispatchReq request);

    DeviceRenewLeaseVO renew(String dispatchKey, DeviceRenewLeaseReq request);

    DeviceReportEventsVO report(DeviceReportEventsReq request);

    DeviceGetDispatchVO getDispatch(String dispatchKey);

    DeviceRecoverVO recover(DeviceRecoverReq request);

    DeviceReportCommandResultVO reportCommand(
            String commandNo, DeviceReportCommandResultReq request);
}
