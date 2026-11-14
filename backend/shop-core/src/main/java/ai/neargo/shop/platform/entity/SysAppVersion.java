package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * App 版本策略：一个 App、一个平台一行（TDD-商户App问题回报与版本 V2–V7）。
 *
 * <p>{@code version}（{@link BaseEntity}）就是运营端看到的 revision：两个人同时改，后提交的那个 10409。
 * {@code appCode} 首期只有 {@code MERCHANT}；POS 安卓 App 以后要强更时加一行数据，不加表。
 */
@Getter
@Setter
@TableName("sys_app_version")
public class SysAppVersion extends BaseEntity {

    private String appCode;
    /** ANDROID / IOS */
    private String platform;
    /** 空 = 不提示更新 */
    private String latestVersion;
    /** 空 = 不拦写；低于它的包写请求回 10426 */
    private String minSupportedVersion;
    /** {@code {"zh-Hant":[…],"zh-Hans":[…],"en":[…],"ar":[…]}} */
    private String notes;
    private String storeUrl;
}
