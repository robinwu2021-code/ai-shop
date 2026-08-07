package ai.neargo.shop.marketing.attribution.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 归因关系（当前）。每人一条，覆盖式更新 —— 历史在 {@link MktAttributionLog} 里。
 *
 * <p>{@code source} 的优先级 {@code STORE_CODE > INVITER > CHANNEL} 是全局唯一口径（B1）：
 * 三种来源同时出现时高优先级胜出；已有高优先级归属时，低优先级不覆盖。
 */
@Getter
@Setter
@TableName("mkt_attribution")
public class MktAttribution extends BaseEntity {

    public static final String STORE_CODE = "STORE_CODE";
    public static final String INVITER = "INVITER";
    public static final String CHANNEL = "CHANNEL";

    private String userNo;
    private String merchantNo;
    private String inviterNo;
    private String channel;
    private String source;

    /** 窗口期结束（默认 30 天）。过期后新的归因可以覆盖任何来源。 */
    private Long expireAt;

    /** 优先级数值：越大越强。 */
    public static int weightOf(String source) {
        return switch (source == null ? "" : source) {
            case STORE_CODE -> 3;
            case INVITER -> 2;
            case CHANNEL -> 1;
            default -> 0;
        };
    }
}
