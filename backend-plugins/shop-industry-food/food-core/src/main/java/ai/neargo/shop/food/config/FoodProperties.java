package ai.neargo.shop.food.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 本地与独立进程共享既有开关；只有受控发布单元选择 migrate。 */
@ConfigurationProperties(prefix = "shop.food")
public record FoodProperties(boolean enabled, @DefaultValue("embedded") Deployment deployment,
        @DefaultValue Migration migration) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public FoodProperties {
        Objects.requireNonNull(deployment, "shop.food.deployment is required");
        Objects.requireNonNull(migration, "shop.food.migration is required");
    }

    public FoodProperties(boolean enabled, Deployment deployment) {
        this(enabled, deployment, new Migration(MigrationMode.VALIDATE));
    }

    public enum Deployment {
        EMBEDDED,
        STANDALONE
    }

    public record Migration(@DefaultValue("validate") MigrationMode mode) {
        public Migration {
            Objects.requireNonNull(mode, "shop.food.migration.mode is required");
        }
    }

    public enum MigrationMode {
        VALIDATE,
        MIGRATE,
        OFF
    }
}
