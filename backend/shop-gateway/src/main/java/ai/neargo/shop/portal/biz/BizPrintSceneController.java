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
public class BizPrintSceneController {
    private final PrintScenePolicyService service0;
    private final PrintActorFactory actors;

    public BizPrintSceneController(PrintScenePolicyService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/scenes")
    public List<SceneVO> listScenes(@ModelAttribute ListScenesQuery query) {
        var actor = actors.merchant(null);
        return service0.listScenes(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/scene-policies")
    public List<ScenePolicyVO> listScenePolicies(@ModelAttribute ListScenePoliciesQuery query) {
        var actor = actors.merchant(null);
        return service0.listScenePolicies(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PutMapping("/biz/print/scene-policies/{scene}")
    public PrintOperation saveScenePolicy(
            @PathVariable("scene") String scene,
            @RequestBody SaveScenePolicyReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.saveScenePolicy(scene, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/scene-policies/{scene}/versions")
    public PageData<PolicyVersionVO> listPolicyVersions(
            @PathVariable("scene") String scene, @ModelAttribute ListPolicyVersionsQuery query) {
        var actor = actors.merchant(null);
        return service0.listPolicyVersions(scene, query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/scenes/{scene}/fields")
    public SceneFieldCatalogVO getSceneFields(
            @PathVariable("scene") String scene, @ModelAttribute GetSceneFieldsQuery query) {
        var actor = actors.merchant(null);
        return service0.getSceneFields(scene, query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/scenes/{scene}/examples")
    public GetSceneExampleVO getSceneExample(
            @PathVariable("scene") String scene, @ModelAttribute GetSceneExampleQuery query) {
        var actor = actors.merchant(null);
        return service0.getSceneExample(scene, query, actor);
    }
}
