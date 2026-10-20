package ai.neargo.shop.beauty.config;

/** Proves local private schema validation only; shared authority readiness is a separate gate. */
public record BeautySchemaReady(String version) {
    public BeautySchemaReady {
        if (!"1".equals(version)) {
            throw new IllegalArgumentException("Beauty P1 requires schema version 1");
        }
    }
}
