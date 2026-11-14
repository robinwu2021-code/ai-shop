package ai.neargo.shop.spi.platform;

import java.util.Optional;

/**
 * 各域 → platform：读写平台可调参数。
 *
 * <p>与 {@link AuditLogPort} 同一个理由：参数存在哪、怎么留痕，是 platform 域的事；
 * 而**需要参数的是各个域**（评分权重在 product、快速退款阈值在 trade）。
 * 让 product 直接依赖 platform 的 Service，两个域就再也拆不开了 ——
 * ArchUnit 第 1 条拦的正是这个。
 */
public interface SettingPort {

    /**
     * @param defaultJson 没配过时返回它 —— 参数表少一行不该让整个页面打不开
     */
    String get(String key, String defaultJson);

    /** 写入并留痕（谁在什么时候改的）。改参数会改变历史数据的呈现，所以留痕不是可选项。 */
    void put(String key, String json, String operatorNo);

    /** Version-aware consumers must not fabricate an initial version from an unsupported provider. */
    default Optional<VersionedValue> getVersioned(String key) {
        throw new UnsupportedOperationException("Versioned settings are unsupported");
    }

    /** A null expected value means create only; an existing value must match version and exact JSON. */
    default VersionedValue compareAndSet(String key, VersionedValue expected, String json, String operatorNo) {
        throw new UnsupportedOperationException("Versioned settings are unsupported");
    }

    record VersionedValue(String json, long version) {
        public VersionedValue {
            if (json == null || version < 0 || version > 9007199254740991L) {
                throw new IllegalArgumentException("Explicit JSON and a safe settings version required");
            }
        }
    }
}
