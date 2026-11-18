package ai.neargo.shop.gateway.core.resource;

import java.util.List;
import java.util.Set;

/** 接口资源表的存取。实现在权限包里；公共件只认这个接口。 */
public interface ResourceStore {

    /** 一次同步写了什么。三个数都是 0 表示库里已经是最新的，一行没动。 */
    record SyncResult(int inserted, int updated, int markedStale) {
        public boolean unchanged() {
            return inserted == 0 && updated == 0 && markedStale == 0;
        }
    }

    /**
     * 把一端启动扫描出来的链接同步进库：新的插入，变了的更新，这次没出现的标为过期。只增改、不删。
     * 人工改过的 {@code status} 不动。
     */
    SyncResult syncScanned(String endCode, List<ApiResource> scanned, String appVersion);

    /** 这一端录入的微服务链接（source=MANUAL，且没有停用）。它们的权限以库为准。 */
    List<ApiResource> loadManual(String endCode);

    /** 把清单里的微服务链接作为种子写入：只在这一行还不存在时插入，已有的行（可能被人调整过）一律不动。返回新插入的条数。 */
    int seedManual(String endCode, List<ApiResource> seeds);

    /** 给「接口资源」页看的一行。 */
    record AdminRow(long id, String endCode, String method, String pattern, String source, String location,
                    String targetPath, String access, String ruleText, String handler, String status, boolean stale,
                    String appVersion, String updatedBy, java.time.LocalDateTime updatedAt) {
    }

    /** 列表的筛选条件；各项为空表示不限。 */
    record AdminQuery(String endCode, String keyword, String source, String status) {
    }

    /** 各类条数。 */
    record AdminCounts(long total, long disabled, long stale, long manual) {
    }

    /** 分页查；按地址、方法排序。 */
    List<AdminRow> page(AdminQuery query, long page, long size);

    long count(AdminQuery query);

    AdminCounts counts();

    java.util.Optional<AdminRow> find(long id);

    /** 改停用状态并记下是谁改的。 */
    void setStatus(long id, String status, String operator);

    /** 这一端被停用的链接，键是「方法 + 空格 + 地址模式」。 */
    Set<String> disabledKeys(String endCode);
}
