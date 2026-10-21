package ai.neargo.shop.pay.points.admin.impl;

import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.pay.points.pool.PointsSwitchService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.spi.points.PointsEarnPort.RuleMode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

@Service
@Transactional("payTxManager")
public class PointsAdministrationServiceImpl implements PointsAdministrationService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsOperationsStore operations;
    private final PointsFactStore facts;
    private final PointTypeService types;
    private final PointsSwitchService switches;
    private final AgreementService agreements;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PointsAdministrationServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            PointsOperationsStore operations,
            PointsFactStore facts,
            PointTypeService types,
            PointsSwitchService switches,
            AgreementService agreements,
            ObjectProvider<Clock> clocks) {
        this.read = read;
        this.config = config;
        this.operations = operations;
        this.facts = facts;
        this.types = types;
        this.switches = switches;
        this.agreements = agreements;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public List<PointType> types() {
        return read.types();
    }

    @Override
    public PointType orderDefault(String market) {
        return read.activeType(market).orElse(null);
    }

    @Override
    public ChangeResult create(Create r, String actor) {
        require(r.command() != null, "POINTS_TYPE_INVALID");
        return execute(
                "CREATE",
                r.requestId(),
                r.command().typeCode(),
                actor,
                r.reason(),
                r,
                true,
                at -> types.create(r.command(), actor));
    }

    @Override
    public ChangeResult transition(TypeChange r, String actor) {
        require(r.action() != null, "POINTS_TYPE_ACTION_INVALID");
        return execute(
                "TYPE_STATE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                false,
                at -> {
                    switch (r.action()) {
                        case ACTIVATE -> types.activate(r.typeCode(), actor);
                        case DRAIN -> types.drain(r.typeCode(), actor);
                        case RETIRE -> types.retire(r.typeCode(), actor);
                        case SET_DEFAULT -> types.setDefault(r.typeCode(), actor);
                        case PAUSE_EARNING, RESUME_EARNING -> {
                            var locked = config.lockRules(r.typeCode());
                            require(locked != null && "ACTIVE".equals(locked.get("status")), "POINTS_TYPE_NOT_ACTIVE");
                            switches.change(new PointsSwitchService.Command(
                                    PointsKeys.position("OS", "TYPE_STATE", r.requestId()),
                                    PointsSwitchService.Scope.TYPE, r.typeCode(), r.typeCode(),
                                    r.action() == TypeAction.RESUME_EARNING, at, actor, r.reason(),
                                    PointsSwitchService.Flow.EARN));
                        }
                    }
                });
    }

    @Override
    public FallbackRule fallback(String type) {
        require(read.type(type).isPresent(), "POINTS_TYPE_UNKNOWN");
        return rule(config.rules(type));
    }

    @Override
    public ChangeResult saveFallback(FallbackChange r, String actor) {
        require(
                r.mode() != null && r.value() >= 0 && r.expectedRevision() >= 0,
                "POINTS_RULE_INVALID");
        return execute(
                "FALLBACK",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                false,
                at -> {
                    var head = config.lockRules(r.typeCode());
                    require(!"RETIRED".equals(head.get("status")), "POINTS_TYPE_RETIRED");
                    var current = rule((String) head.get("fallback_rule_json"));
                    require(
                            current.revision() == r.expectedRevision(),
                            "POINTS_RULE_REVISION_CONFLICT");
                    var next =
                            new FallbackRule(
                                    Math.addExact(current.revision(), 1), r.mode(), r.value());
                    config.saveRules(
                            r.typeCode(),
                            number(head, "version"),
                            json.writeValueAsString(next),
                            actor);
                });
    }

    @Override
    public SwitchPage switches(String type, long after, int limit) {
        page(type, after, limit);
        var rows =
                operations.switches(type, after, limit).stream()
                        .map(
                                row -> {
                                    var c =
                                            json.readValue(
                                                    (String) row.get("payload_json"),
                                                    PointsSwitchService.Command.class);
                                    return new SwitchView(
                                            number(row, "id"),
                                            c.scope(),
                                            c.typeCode(),
                                            c.subjectNo(),
                                            c.enabled(),
                                            c.flow(),
                                            c.actor(),
                                            c.reason(),
                                            c.at());
                                })
                        .toList();
        return new SwitchPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public ChangeResult changeSwitch(SwitchChange r, String actor) {
        require(
                r.scope() != PointsSwitchService.Scope.SCENE || PayModes.isValid(r.subjectNo()),
                "POINTS_SWITCH_SUBJECT_INVALID");
        require(
                r.scope() != PointsSwitchService.Scope.CLIENT || PayScenes.isValid(r.subjectNo()),
                "POINTS_SWITCH_SUBJECT_INVALID");
        return execute(
                "SWITCH",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                false,
                at ->
                        switches.change(
                                new PointsSwitchService.Command(
                                        PointsKeys.position("OS", "SWITCH", r.requestId()),
                                        r.scope(),
                                        r.typeCode(),
                                        r.subjectNo(),
                                        r.enabled(),
                                        at,
                                        actor,
                                        r.reason(),
                                        r.flow())));
    }

    @Override
    public Terms terms(String type) {
        require(read.type(type).isPresent(), "POINTS_TYPE_UNKNOWN");
        var row = config.agreement(PointsKeys.position("T", type, "CURRENT_TERMS"));
        return row == null ? null : json.readValue((String) row.get("payload_json"), Terms.class);
    }

    @Override
    public ChangeResult publishTerms(TermsChange r, String actor) {
        return execute(
                "TERMS",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                false,
                at -> agreements.publishTerms(r.typeCode(), r.version(), r.text(), actor));
    }

    @Override
    public AgreementPage agreements(String type, String store, long after, int limit) {
        page(type, after, limit);
        require(nonblank(store) && !"PLATFORM".equals(store), "POINTS_STORE_REQUIRED");
        var rows =
                operations.agreements(type, store, after, limit).stream()
                        .map(
                                row ->
                                        new AgreementView(
                                                number(row, "id"),
                                                json.readValue(
                                                        (String) row.get("payload_json"),
                                                        AgreementSnapshot.class),
                                                (String) row.get("state"),
                                                number(row, "version")))
                        .toList();
        return new AgreementPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    private ChangeResult execute(
            String operation,
            String requestId,
            String type,
            String actor,
            String reason,
            Object request,
            boolean creating,
            LongConsumer work) {
        require(
                nonblank(requestId)
                        && requestId.length() <= 120
                        && nonblank(type)
                        && nonblank(actor)
                        && nonblank(reason),
                "POINTS_OPERATOR_INPUT_INVALID");
        if (request instanceof TypeChange change && change.action() == TypeAction.SET_DEFAULT) {
            String market = read.type(type).orElseThrow(() -> new IllegalArgumentException("POINTS_TYPE_UNKNOWN")).market();
            config.lockMarketTypes(market);
        }
        if (!creating) {
            if ("*".equals(type)) {
                read.types().stream()
                        .sorted(Comparator.comparing(PointType::typeCode))
                        .forEach(t -> config.lockTypeAndPool(t.typeCode()));
            } else {
                config.lockTypeAndPool(type);
            }
        }
        String key = PointsKeys.position("OC", operation, requestId);
        String body = json.writeValueAsString(new Envelope(operation, request, actor));
        var prior = facts.lockInbox(key);
        if (prior != null) {
            var receipt = json.readValue((String) prior.get("payload_json"), Receipt.class);
            require(body.equals(receipt.commandBody()), "POINTS_OPERATOR_REQUEST_CONFLICT");
            return receipt.result();
        }
        long at = clock.millis();
        work.accept(at);
        var result = new ChangeResult(requestId, operation, type, at);
        String payload = json.writeValueAsString(new Receipt(body, result));
        facts.recordEvent(type, key, PointsDigests.sha256(payload), payload, "CONFIG_APPLIED");
        return result;
    }

    private FallbackRule rule(String body) {
        return body == null
                ? new FallbackRule(0, RuleMode.RATIO, 0)
                : json.readValue(body, FallbackRule.class);
    }

    private void page(String type, long after, int limit) {
        require(nonblank(type) && after >= 0 && limit >= 1 && limit <= 200, "POINTS_PAGE_INVALID");
        require("*".equals(type) || read.type(type).isPresent(), "POINTS_TYPE_UNKNOWN");
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private boolean nonblank(String s) {
        return s != null && !s.isBlank();
    }

    private void require(boolean c, String code) {
        if (!c) {
            throw new IllegalArgumentException(code);
        }
    }

    public record Envelope(String operation, Object request, String actor) {}

    public record Receipt(String commandBody, ChangeResult result) {}
}
