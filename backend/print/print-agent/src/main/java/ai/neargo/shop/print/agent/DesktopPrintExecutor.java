package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;

public interface DesktopPrintExecutor {
    QueueObservation submit(LocalDispatch dispatch);

    QueueObservation query(LocalDispatch dispatch);

    QueueObservation cancel(LocalDispatch dispatch);
}
