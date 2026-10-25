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

public interface PrintScenePolicyService {
    List<SceneVO> listScenes(ListScenesQuery query, PrintActor actor);

    List<ScenePolicyVO> listScenePolicies(ListScenePoliciesQuery query, PrintActor actor);

    PrintOperation saveScenePolicy(String scene, SaveScenePolicyReq request, PrintActor actor);

    PageData<PolicyVersionVO> listPolicyVersions(
            String scene, ListPolicyVersionsQuery query, PrintActor actor);

    SceneFieldCatalogVO getSceneFields(String scene, GetSceneFieldsQuery query, PrintActor actor);

    GetSceneExampleVO getSceneExample(String scene, GetSceneExampleQuery query, PrintActor actor);
}
