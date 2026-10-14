package ai.neargo.shop.link.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 短链（TDD-收件人物流触达与分享裂变 §4.2）。
 *
 * <p>一行就是一条「短码 → 长目标」的映射。发货短信里放的是 {@code s.hxmall.top/<code>}，
 * 点开查回 {@link #target}（通常是微信 URL Link）再 302 过去。
 *
 * <p>目标可改而短码不变（{@link #target} 单列更新）：微信 URL Link 会过期，
 * 换新的只改这一列，已经发出去的短信还点得开。
 */
@Getter
@Setter
@TableName("lnk_short")
public class ShortLink extends BaseEntity {

    /** 发货看件 */
    public static final String BIZ_SHIP_TRACK = "SHIP_TRACK";

    /** 短码。放进短信的就是它 */
    private String code;

    /** 302 跳向的长链接 */
    private String target;

    /** 业务类型，见 {@link #BIZ_SHIP_TRACK} */
    private String bizType;

    /** 业务单号（子单号），排查用 */
    private String bizRef;

    /** 点击次数，每次 302 自增 */
    private Long hits;

    /** 过期时间。空 = 不过期 */
    private LocalDateTime expiresAt;
}
