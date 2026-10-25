package ai.neargo.shop.printing.center.service.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.operation.PrintOperationService;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.source.PrintSourceRegistry;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintScenePolicyServiceImpl implements PrintScenePolicyService {
    private final PrnScenePolicyMapper policies;
    private final PrnPolicyVersionMapper versions;
    private final PrintSceneRegistry scenes;
    private final PrintSourceRegistry sources;
    private final PrintJsonCodec json;
    private final PrintOperationService ops;

    public PrintScenePolicyServiceImpl(
            PrnScenePolicyMapper p,
            PrnPolicyVersionMapper v,
            PrintSceneRegistry s,
            PrintSourceRegistry r,
            PrintJsonCodec j,
            PrintOperationService o) {
        policies = p;
        versions = v;
        scenes = s;
        sources = r;
        json = j;
        ops = o;
    }

    public List<SceneVO> listScenes(ListScenesQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        return scenes.all().stream()
                .sorted(Comparator.comparing(PrintSceneRegistry.SceneDefinition::code))
                .map(
                        s -> {
                            var types = new HashSet<String>();
                            s.definition().path("sourceTypes").forEach(t -> types.add(t.asText()));
                            return new SceneVO(
                                    s.code(),
                                    s.definition().path("title").asText(),
                                    types.stream()
                                            .anyMatch(type -> sources.supports(type, s.code())),
                                    types,
                                    s.definition().path("manualMode").asText(),
                                    1,
                                    s.templates().keySet(),
                                    new java.util.HashSet<>(
                                            java.util.stream.StreamSupport.stream(
                                                            s.definition()
                                                                    .path("triggers")
                                                                    .spliterator(),
                                                            false)
                                                    .map(tools.jackson.databind.JsonNode::asText)
                                                    .toList()));
                        })
                .toList();
    }

    public List<ScenePolicyVO> listScenePolicies(ListScenePoliciesQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        return policies.selectList(PrintSupport.<PrnScenePolicy>scoped(scope(a))).stream()
                .map(
                        p ->
                                new ScenePolicyVO(
                                        p.getPolicyNo(),
                                        p.getScene(),
                                        p.getEnabled() == 1,
                                        json.freeze(p),
                                        p.getRevision()))
                .toList();
    }

    public PrintOperation saveScenePolicy(String scene, SaveScenePolicyReq r, PrintActor a) {
        return ops.execute(
                a,
                "SAVE_SCENE_POLICY",
                json.freeze(Map.of("scene", scene, "request", r)).sha256(),
                () -> {
                    require(a.storeNo().equals(r.storeNo()), "print.forbidden");
                    var def = scenes.require(scene);
                    var policy = r.policy();
                    require(
                            policy != null
                                    && policy.submitTimeoutSeconds() >= 5
                                    && policy.submitTimeoutSeconds() <= 3600
                                    && policy.resultTimeoutSeconds() >= 5
                                    && policy.resultTimeoutSeconds() <= 3600
                                    && policy.maxRetryCount() >= 0
                                    && policy.maxRetryCount() <= 10
                                    && policy.staleAfterSeconds() >= 60
                                    && policy.staleAfterSeconds() <= 86400
                                    && policy.backlogAlertSeconds() >= 5,
                            "print.invalidPolicy");
                    var allowedTriggers = new java.util.HashSet<String>();
                    def.definition().path("triggers").forEach(t -> allowedTriggers.add(t.asText()));
                    require(
                            allowedTriggers.containsAll(policy.triggerTypes()),
                            "print.invalidTrigger");
                    var notifications = policy.notificationPolicy();
                    require(
                            notifications != null
                                    && java.util.Set.of(
                                                    "onIncident",
                                                    "onBatchResolved",
                                                    "channels",
                                                    "recipientRoleKeys")
                                            .containsAll(notifications.keySet()),
                            "print.invalidNotificationPolicy");
                    var notificationTree = json.tree(notifications);
                    for (var channel : notificationTree.path("channels"))
                        require(
                                java.util.Set.of("IN_APP", "PUSH").contains(channel.asText()),
                                "print.invalidNotificationChannel");
                    for (var role : notificationTree.path("recipientRoleKeys"))
                        require(
                                java.util.Set.of("OWNER", "MANAGER", "CLERK", "STORE_MANAGER")
                                        .contains(role.asText()),
                                "print.invalidNotificationRole");
                    if (policy.enabled()) {
                        boolean supported = false;
                        for (var type : def.definition().path("sourceTypes"))
                            if (sources.supports(type.asText(), scene)) supported = true;
                        require(supported, "print.sourceUnavailable");
                    }
                    var p =
                            policies.selectOne(
                                    PrintSupport.<PrnScenePolicy>scoped(scope(a))
                                            .eq("scene", scene));
                    if (p == null) {
                        require(
                                r.expectedRevision() != null && r.expectedRevision() == 0,
                                "print.revisionConflict");
                        p = init(new PrnScenePolicy(), scope(a));
                        p.setPolicyNo(no("SP"));
                        p.setStoreNo(a.storeNo());
                        p.setScene(scene);
                    } else revision(p, r.expectedRevision());
                    p.setEnabled(policy.enabled() ? 1 : 0);
                    p.setCritical(policy.critical() ? 1 : 0);
                    p.setTriggerTypes(json.write(policy.triggerTypes()));
                    p.setFallbackPrinterNo(policy.fallbackPrinterNo());
                    p.setSubmitTimeoutSeconds(policy.submitTimeoutSeconds());
                    p.setResultTimeoutSeconds(policy.resultTimeoutSeconds());
                    p.setMaxRetryCount(policy.maxRetryCount());
                    p.setBacklogAlertSeconds(policy.backlogAlertSeconds());
                    p.setStaleAfterSeconds(policy.staleAfterSeconds());
                    p.setNotificationPolicy(json.write(policy.notificationPolicy()));
                    p.setOutputOptions(json.write(policy.outputOptions()));
                    p.setPolicySnapshotHash(json.freeze(policy).sha256());
                    var v = init(new PrnPolicyVersion(), scope(a));
                    v.setPolicyVersionNo(no("PV"));
                    v.setPolicyNo(p.getPolicyNo());
                    v.setVersionNumber(
                            versions.selectCount(
                                                    PrintSupport.<PrnPolicyVersion>owned(scope(a))
                                                            .eq("policy_no", p.getPolicyNo()))
                                            .intValue()
                                    + 1);
                    v.setEffectiveFrom(now());
                    p.setCurrentVersionNo(v.getPolicyVersionNo());
                    v.setPolicySnapshot(json.write(p));
                    v.setContentHash(p.getPolicySnapshotHash());
                    v.setCreatedBy(a.actorNo());
                    versions.insert(v);
                    p.setCurrentVersionNo(v.getPolicyVersionNo());
                    if (p.getId() == null) policies.insert(p);
                    else {
                        p.setRevision(p.getRevision() + 1);
                        p.setUpdatedAt(now());
                        policies.updateById(p);
                    }
                    return succeeded(
                            "SAVE_SCENE_POLICY",
                            "POLICY_VERSION",
                            v.getPolicyVersionNo(),
                            json.freeze(Map.of("policyVersionNo", v.getPolicyVersionNo())));
                });
    }

    public PageData<PolicyVersionVO> listPolicyVersions(
            String scene, ListPolicyVersionsQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var h =
                policies.selectOne(
                        PrintSupport.<PrnScenePolicy>scoped(scope(a)).eq("scene", scene));
        require(h != null, "print.notFound");
        var p =
                versions.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnPolicyVersion>owned(scope(a))
                                .eq("policy_no", h.getPolicyNo())
                                .orderByDesc("version_number"));
        return PageData.of(
                p.getRecords().stream()
                        .map(
                                v ->
                                        new PolicyVersionVO(
                                                v.getPolicyVersionNo(),
                                                v.getVersionNumber(),
                                                json.freeze(v.getPolicySnapshot()),
                                                instant(v.getEffectiveFrom())))
                        .toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public SceneFieldCatalogVO getSceneFields(String scene, GetSceneFieldsQuery q, PrintActor a) {
        require(
                a.storeNo().equals(q.storeNo())
                        && q.payloadSchemaVersion() != null
                        && q.payloadSchemaVersion() == 1,
                "print.schemaMismatch");
        return new SceneFieldCatalogVO(scene, json.freeze(scenes.require(scene).definition()));
    }

    public GetSceneExampleVO getSceneExample(String scene, GetSceneExampleQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        require(
                scenes.require(scene).templates().containsKey(q.variantCode()),
                "print.variantUnavailable");
        return new GetSceneExampleVO(
                scene,
                json.freeze(scenes.require(scene).example()),
                json.freeze(
                        scenes.require(scene).templates().get(q.variantCode()).path("content")));
    }
}
