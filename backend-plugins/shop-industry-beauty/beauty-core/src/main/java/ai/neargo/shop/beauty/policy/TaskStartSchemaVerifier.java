package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import javax.sql.DataSource;

/**
 * Capability inspection happens before the canonical source roots, never under a Native effect
 * transaction.
 */
public final class TaskStartSchemaVerifier {
    public enum Installation {
        ABSENT,
        INSTALLED,
        CORRUPT
    }

    private final DataSource source;

    public TaskStartSchemaVerifier(DataSource source) {
        this.source = source;
    }

    public Installation installation() {
        var extension = TaskStartSchemaExtension.inspect(source).installation();
        if (extension == TaskStartSchemaExtension.Installation.ABSENT) return Installation.ABSENT;
        if (extension == TaskStartSchemaExtension.Installation.CORRUPT
                || new TaskActionSchemaVerifier(source).installation()
                        != TaskActionSchemaVerifier.Installation.INSTALLED
                || new TaskAcceptanceSchemaVerifier(source).installation()
                        != TaskAcceptanceSchemaVerifier.Installation.INSTALLED)
            return Installation.CORRUPT;
        return Installation.INSTALLED;
    }

    public void requireAvailable() {
        var state = installation();
        if (state != Installation.INSTALLED)
            throw BizException.of(
                    ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                    state == Installation.ABSENT
                            ? "TASK_START_SCHEMA_UNAVAILABLE"
                            : "TASK_START_SCHEMA_CORRUPT");
    }
}
