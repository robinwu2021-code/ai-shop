package ai.neargo.shop.food.policy;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** Strict readers belong only to new frozen formats; original mapper and legacy codecs stay untouched. */
final class PublicationJsonPolicy {
    private static final Map<LogicalType, List<CoercionInputShape>> FORBIDDEN = Map.of(
            LogicalType.Textual, List.of(CoercionInputShape.Integer, CoercionInputShape.Float,
                    CoercionInputShape.Boolean, CoercionInputShape.EmptyArray, CoercionInputShape.EmptyObject),
            LogicalType.Boolean, List.of(CoercionInputShape.Integer, CoercionInputShape.Float,
                    CoercionInputShape.String, CoercionInputShape.EmptyString, CoercionInputShape.EmptyArray,
                    CoercionInputShape.EmptyObject),
            LogicalType.Integer, List.of(CoercionInputShape.Float, CoercionInputShape.Boolean,
                    CoercionInputShape.String, CoercionInputShape.EmptyString, CoercionInputShape.EmptyArray,
                    CoercionInputShape.EmptyObject));
    private PublicationJsonPolicy() { }

    /** Reuse an already strict reader during recursive validation, without a global mapper cache or mutation. */
    static ObjectMapper strict(ObjectMapper original) {
        if (isStrict(original)) return original;
        var builder = original.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS);
        FORBIDDEN.forEach((type, shapes) -> {
            builder.withCoercionConfig(type, config -> shapes.forEach(
                    shape -> config.setCoercion(shape, CoercionAction.Fail)));
            for (Class<?> target : targets(type)) builder.withCoercionConfig(target, config -> shapes.forEach(
                    shape -> config.setCoercion(shape, CoercionAction.Fail)));
        });
        return builder.build();
    }

    private static boolean isStrict(ObjectMapper json) {
        if (!json.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                || json.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                || json.isEnabled(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)) return false;
        for (var entry : FORBIDDEN.entrySet()) {
            for (Class<?> target : targets(entry.getKey())) for (var shape : entry.getValue()) {
                if (json.deserializationConfig().findCoercionAction(entry.getKey(), target, shape)
                        != CoercionAction.Fail) return false;
            }
        }
        return true;
    }

    private static List<Class<?>> targets(LogicalType type) {
        return switch (type) {
            case Textual -> List.of(String.class);
            case Boolean -> List.of(Boolean.class, boolean.class);
            case Integer -> List.of(Integer.class, int.class, Long.class, long.class);
            default -> throw new IllegalArgumentException("Unsupported strict publication scalar type");
        };
    }
}
