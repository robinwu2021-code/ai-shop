package ai.neargo.shop.marketing.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 门店访问埋点（append-only）：扫码落地那一刻记一行，<b>不要求登录</b>。
 *
 * <p><b>它与 {@code mkt_attribution_log} 是两件事</b>：归因回答「这个用户属于谁」
 * （一人一条有效、有窗口期），访问回答「这家店最近被扫了多少次」。
 * 混用会让归因窗口被扫码反复刷新 —— 而归因决定费率档。
 *
 * <p>获客漏斗「扫码 → 进店 → 注册 → 首单」里，后三段都能从归因留痕算出来，
 * <b>只有第一段没有采集</b>：{@code /mp/store/by-code} 只解析不落行，
 * 而 {@code /mp/store/{no}/enter} 要求登录。这张表补的就是那一段。
 */
@Getter
@Setter
@TableName("mkt_store_visit")
public class MktStoreVisit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String visitNo;

    /**
     * 商家主体。<b>粒度是主体不是门店</b> —— 店铺码是一主体一码
     * （{@code mch_entity.store_code}），物理上分不出扫的是哪家分店。
     */
    private String entityNo;

    /** 扫的是哪个码。将来一码多物料时靠它分辨来源 */
    private String storeCode;

    /** 为将来「一店一码」预留；现在恒为空 */
    private String storeNo;

    /**
     * 登录用户；<b>为空 = 匿名访客</b>。
     *
     * <p>空值是这一列的<b>要点而不是缺陷</b>：漏斗最宽、也唯一没被采集过的那一层
     * 就是「扫了码但还没注册的人」。设成非空等于把要测的东西测没。
     */
    private String userNo;

    /**
     * 端上生成并持久化的设备号。
     *
     * <p><b>UV 与防刷都只能靠它</b> —— 匿名访客没有 {@code userNo}，
     * 按人去重时它是唯一的抓手。
     */
    private String deviceId;

    private String ip;

    /** UA 摘要。<b>不存原文</b> —— 原文可用于指纹，属个人信息。 */
    private String uaHash;

    private Long at;

    private String tenantNo;
    private LocalDateTime createdAt;
}
