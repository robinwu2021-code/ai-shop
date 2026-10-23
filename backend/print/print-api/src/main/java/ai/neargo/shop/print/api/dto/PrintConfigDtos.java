package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintConfigDtos {
    private PrintConfigDtos() {}

    public record Policy(
            boolean enabled,
            boolean critical,
            Set<String> triggerTypes,
            String fallbackPrinterNo,
            int submitTimeoutSeconds,
            int resultTimeoutSeconds,
            int maxRetryCount,
            int backlogAlertSeconds,
            int staleAfterSeconds,
            Map<String, Object> notificationPolicy,
            PrintOutputOptions outputOptions) {
        public Policy {
            triggerTypes = Set.copyOf(Objects.requireNonNull(triggerTypes, "triggerTypes"));
        }
    }

    public record RoutePredicate(List<RouteCondition> all) {
        public RoutePredicate {
            all = List.copyOf(all);
        }
    }

    public record RouteCondition(String field, String op, String value) {}

    public record RouteRule(
            String ruleKey, int priority, RoutePredicate match, List<RouteTarget> targets) {
        public RouteRule {
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }
    }

    public record RouteTarget(
            String targetKey,
            String printerNo,
            int copies,
            boolean required,
            TemplateSelection templateSelection) {}

    public record RouteMatch(
            String documentKey,
            String ruleKey,
            String printerNo,
            int copies,
            boolean required,
            TemplateSelection templateSelection) {}

    public record ListScenesQuery(String storeNo) {}

    public record SceneVO(
            String scene,
            String title,
            boolean sourceAvailable,
            Set<String> sourceTypes,
            String manualMode,
            int payloadSchemaVersion,
            Set<String> variants,
            Set<String> triggers) {
        public SceneVO {
            sourceTypes = Set.copyOf(Objects.requireNonNull(sourceTypes, "sourceTypes"));
            variants = Set.copyOf(Objects.requireNonNull(variants, "variants"));
        }
    }

    public record ListScenePoliciesQuery(String storeNo) {}

    public record ScenePolicyVO(
            String policyNo, String scene, boolean enabled, FrozenJson policy, long revision) {}

    public record SaveScenePolicyReq(String storeNo, Long expectedRevision, Policy policy) {}

    public record ListPolicyVersionsQuery(String storeNo, Integer page, Integer size) {}

    public record PolicyVersionVO(
            String policyVersionNo, int versionNumber, FrozenJson policy, Instant effectiveFrom) {}

    public record ListTemplatesQuery(
            String storeNo, String scene, String variantCode, Integer page, Integer size) {}

    public record TemplateVO(
            String templateNo,
            String scene,
            String variantCode,
            String name,
            String scopeKey,
            String currentVersionNo,
            String status,
            long revision) {}

    public record CreateTemplateReq(
            String storeNo, String scene, String variantCode, String name) {}

    public record CreateTemplateVersionReq(
            Long expectedRevision,
            String format,
            int schemaVersion,
            int payloadSchemaVersion,
            int paperWidthMm,
            Map<String, Object> content,
            Set<String> compatibleCapabilities) {
        public CreateTemplateVersionReq {
            compatibleCapabilities =
                    Set.copyOf(
                            Objects.requireNonNull(
                                    compatibleCapabilities, "compatibleCapabilities"));
        }
    }

    public record UpdateTemplateDraftReq(
            Long expectedRevision,
            int payloadSchemaVersion,
            int paperWidthMm,
            Map<String, Object> content,
            Set<String> compatibleCapabilities) {
        public UpdateTemplateDraftReq {
            compatibleCapabilities =
                    Set.copyOf(
                            Objects.requireNonNull(
                                    compatibleCapabilities, "compatibleCapabilities"));
        }
    }

    public record PreviewTemplateReq(PrintSourceReference sampleSourceRef, Integer paperWidthMm) {}

    public record PreviewVO(
            String scene,
            String variantCode,
            int paperWidthMm,
            int payloadSchemaVersion,
            String templateVersionNo,
            PrintDocument document,
            String documentHash,
            String rendererVersion,
            List<String> warnings) {
        public PreviewVO {
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    public record PublishTemplateReq(Long expectedRevision, Long expectedTemplateRevision) {}

    public record ListTemplateVersionsQuery(Integer page, Integer size) {}

    public record TemplateVersionVO(
            String templateVersionNo,
            String templateNo,
            String status,
            int payloadSchemaVersion,
            int paperWidthMm,
            Map<String, Object> content,
            long revision) {}

    public record ListRoutesQuery(String storeNo, String scene, Integer page, Integer size) {}

    public record RouteVO(
            String routeNo, String scene, String currentVersionNo, String status, long revision) {}

    public record CreateRouteReq(String storeNo, String scene) {}

    public record CreateRouteVersionReq(Long expectedRevision, List<RouteRule> rules) {
        public CreateRouteVersionReq {
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        }
    }

    public record DryRunRouteReq(PrintSourceReference sourceRef) {}

    public record RouteDryRunVO(List<RouteMatch> results, List<String> warnings) {
        public RouteDryRunVO {
            results = List.copyOf(Objects.requireNonNull(results, "results"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    public record PublishRouteReq(Long expectedRevision, Long expectedRouteRevision) {}

    public record ListRouteVersionsQuery(Integer page, Integer size) {}

    public record RouteVersionVO(
            String routeVersionNo,
            String routeNo,
            String status,
            List<RouteRule> rules,
            long revision) {
        public RouteVersionVO {
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        }
    }

    public record PreviewResolutionReq(
            String storeNo,
            String scene,
            PrintSourceReference sourceRef,
            String printerNo,
            TemplateSelection templateSelection,
            boolean sample) {}

    public record TemplateResolutionPreviewVO(
            String scene,
            String variantCode,
            ResolvedTemplate selected,
            PrintDocument documentPreview,
            List<String> blockers,
            Instant asOf) {
        public TemplateResolutionPreviewVO {
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        }
    }

    public record GetSceneFieldsQuery(String storeNo, Integer payloadSchemaVersion) {}

    public record SceneFieldCatalogVO(String scene, FrozenJson catalog) {}

    public record GetSceneExampleQuery(String storeNo, String variantCode) {}

    public record GetSceneExampleVO(String scene, FrozenJson example, FrozenJson baseTemplate) {}
}
