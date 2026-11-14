package ai.neargo.shop.beauty.config;

/** Version9 proves only the frozen 28-table V1-V9 base, independent of optional execution installation. */
public record BeautySchemaReady(String version) {
    public BeautySchemaReady {
        if (!"9".equals(version)) {
            throw new IllegalArgumentException("Beauty base requires schema version 9");
        }
    }
}
