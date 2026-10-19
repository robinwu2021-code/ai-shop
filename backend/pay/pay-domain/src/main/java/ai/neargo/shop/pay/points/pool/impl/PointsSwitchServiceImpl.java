package ai.neargo.shop.pay.points.pool.impl;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.policy.SwitchPolicy;
import ai.neargo.shop.pay.points.pool.PointsSwitchService;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Switch transitions and pause history are atomic metadata, separate from existing financial
 * promises.
 */
@Service
@Transactional("payTxManager")
public class PointsSwitchServiceImpl implements PointsSwitchService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsFactStore facts;
    private final LifecycleStore store;
    private final ObjectMapper json = new ObjectMapper();

    public PointsSwitchServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            PointsFactStore facts,
            LifecycleStore store) {
        this.read = read;
        this.config = config;
        this.facts = facts;
        this.store = store;
    }

    @Override
    public void change(Command c) {
        require(
                c != null
                        && nonblank(c.eventNo())
                        && c.scope() != null
                        && nonblank(c.typeCode())
                        && nonblank(c.subjectNo())
                        && c.at() >= 0
                        && nonblank(c.actor())
                        && nonblank(c.reason()),
                "POINTS_SWITCH_INPUT_INVALID");
        if (c.scope() == Scope.GLOBAL || c.scope() == Scope.REGION) {
            require(
                    "*".equals(c.typeCode())
                            && (c.scope() == Scope.REGION || "ALL".equals(c.subjectNo())),
                    "POINTS_SWITCH_SCOPE_INVALID");
            read.types().stream()
                    .sorted(Comparator.comparing(PointType::typeCode))
                    .forEach(type -> config.lockTypeAndPool(type.typeCode()));
        } else {
            config.lockTypeAndPool(c.typeCode());
        }
        String payload = json.writeValueAsString(c);
        var prior = facts.lockInbox(c.eventNo());
        if (prior != null) {
            require(payload.equals(prior.get("payload_json")), "POINTS_SWITCH_EVENT_CONFLICT");
            return;
        }
        String key = SwitchPolicy.key(c.scope(), c.typeCode(), c.subjectNo());
        if (c.scope() == Scope.TYPE) {
            require(c.subjectNo().equals(c.typeCode()), "POINTS_SWITCH_SCOPE_INVALID");
        }
        var current = store.lockSwitch(SwitchPolicy.scope(c), key);
        if (current != null) {
            var original = json.readValue((String) current.get("payload_json"), Command.class);
            require(c.at() >= original.at(), "POINTS_SWITCH_TIME_CONFLICT");
        }
        boolean previous = current == null || "ON".equals(current.get("state"));
        store.saveSwitch(c, key, payload, previous);
        facts.recordEvent(c.typeCode(), c.eventNo(), hash(payload), payload, "SWITCHED");
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
