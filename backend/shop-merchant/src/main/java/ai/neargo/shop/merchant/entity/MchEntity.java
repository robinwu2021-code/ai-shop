package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家主体。
 *
 * <p><b>评分是冗余列不是实时算的</b>：评分要出现在每一张商品卡上，实时聚合评价表会让首页
 * 每次都扫一遍评价。写评价时更新这几列（B4 口径：评价均分 ×0.8 + 订单量对数 ×0.2）。
 *
 * <p>{@code breachCount} 公示在报价卡上 —— ADR-003 用「事后信用」替代「事前审核」，
 * 这一列就是那个决策的落点，不能只当统计字段。
 */
@Getter
@Setter
@TableName("mch_entity")
public class MchEntity extends BaseEntity {

    private String entityNo;
    private String name;
    private String logo;

    /** PERSONAL / INDIVIDUAL / COMPANY —— 主体类型，决定资质要求与结算通道。 */
    private String legalForm;

    /**
     * 资金路径（轴②）：{@code AGGREGATED} 归集 / {@code DIRECT} 直连。
     *
     * <p><b>能走哪条由商户类型决定，不是平台自选</b>（ADR-017 §3.2）——
     * 自然人开不出票，平台按全额确认收入而成本不可税前扣除，所以禁归集；
     * 自产农产品例外（平台可自开农产品收购发票）。
     */
    private String fundsMode;

    /**
     * 是否农业生产者。为 1 时**允许走归集路径**（自然人的例外）——
     * 平台可自开农产品收购发票，成本有合法凭证。
     *
     * <p>与商品侧「自产农产品」品类<b>两者都满足</b>才适用：
     * 合作社卖百货不算，公司卖自产农产品同样算。
     */
    private Integer isAgriProducer;

    /**
     * 经营资格（轴①，法定）：{@code REGISTERED} / {@code EXEMPT} / {@code UNREGISTERED}。
     *
     * <p><b>决定能不能交易，与通道无关</b> —— {@code UNREGISTERED} 是违法经营，
     * 平台不得提供交易能力；{@code EXEMPT} 是电商法 §10 明文豁免的<b>合法经营者</b>，
     * 不是「无资质」。
     */
    private String bizQualification;

    /**
     * 免登记情形（电商法 §10 四类）：{@code AGRI}/{@code HANDCRAFT}/{@code SERVICE}/{@code PETTY}。
     *
     * <p>⚠️ <b>只有 {@code PETTY} 受 10 万元/年 约束</b>，其余三类无金额上限 ——
     * 农户卖 50 万自产柿饼仍然免登记。四类混起来监控会误伤前三类。
     */
    private String exemptType;

    /** 分层（P-11.1.6）：一期只留字段，为引入大商家预留。 */
    private String tier;

    private String description;
    /*
     * address / open_hours **不在这里** —— 它们归门面表 mch_store（V42）。
     * ADR-011 的判据：通道或税务认的归主体，顾客感知的归门店。
     * 顾客说「楼下那家，八点关门」指的是门店，不是营业执照上的注册地址。
     */

    /** 店主的 C 端用户号 —— B 端权限的源头（BizIdentityResolver 靠它解析 entityNo）。 */
    private String ownerUserNo;

    /** 综合评分 ×10 存整数：浮点在对账与排序上会咬人，展示时除以 10。 */
    private Integer rating;
    private Integer ratingCount;
    private Integer salesCount;
    private Integer goodsCount;

    /** 三维度评分（商品/服务/时效），同样 ×10。 */
    private Integer scoreGoods;
    private Integer scoreService;
    private Integer scoreSpeed;

    /** 资质认证标（P-11.1.2 授予/撤销）。 */
    private Boolean verified;

    /** 选定报价后不履约的次数，>0 在报价卡公示（ADR-003）。 */
    private Integer breachCount;

    /** JSON 数组，展示用标签。 */
    private String tags;

    /**
     * 店铺码：印在包装袋/贴纸上的短码（B-11.2.6）。
     * 与 {@code entityNo} 分开是刻意的 —— entityNo 出现在日志与对账里，
     * 而店铺码是给陌生人扫的，需要能单独作废重发。
     */
    private String storeCode;

    /**
     * 店铺小程序码的 PNG base64（V164）。
     *
     * <p><b>生成一次就存着</b>：{@code wxacode.getUnlimited} 是永久码且每个 appid
     * 总量有限（十万级），每次请求现调会把额度耗在刷新页面上 ——
     * 而额度用尽之后，新入驻的商家再也拿不到码。
     */
    private String acodeBase64;

    private Long joinedAt;

    /** APPLYING / ACTIVE / SUSPENDED / BANNED —— 只有 ACTIVE 能上架与收款。 */
    private String status;
    /**
     * 经营范围 COMMUNITY/CITY/ALL —— **决定这家店的货在 C 端能被谁看到**。
     * 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款），
     * 选小了则整片小区的人都搜不到这家店。
     */
    private String serviceScope;

    /**
     * 履约能力：{@code PICKUP} 靠自提点 / {@code ONSITE} 上门或同城配送 /
     * {@code SHIPPING} 快递无半径（ADR-013 阶段二）。
     *
     * <p><b>与地理覆盖正交</b>：这一列只说「怎么送到你手上」，能卖给谁看
     * {@code mch_service_area}。词典里「Scope ≠ Fulfilment」那条规矩，
     * 到这一版才第一次真正成立。
     */
    private String fulfillmentReach;

    /** 仅 scope=CITY 时有意义。覆盖的社区是多对多，见 mch_entity_community。 */
    private String serviceCityCode;


    /** 本店是否开启积分。四级串联的最后一级。 */
    private Boolean pointsEnabled;

    /** 平台按行业强制开，商家不可自行关闭。 */
    private Boolean pointsForced;

    /**
     * 所属行业（{@code sys_industry.industry}）。
     *
     * <p><b>与商品类目是两个维度</b>：行业挂商家，类目挂商品。
     * 它决定商家<b>可选的主体类型</b> —— 线上业态不能选小微。
     */
    private String industry;

    /**
     * 已获授权的经营类目编码，JSON 数组如 {@code ["FRESH_VEG","FOOD"]}。入驻时申请、平台审核时授权。
     *
     * <p>与 {@code prd_category.required_code} 比对，决定这家店能不能把商品**上架**到某个类目下。
     * <b>空 = 没有任何特许类目</b>，只能上架无门槛的类目 —— 而不是「不限制」。
     * 默认放开的话，卖烧烤的第二天就能上架生鲜，出事才发现平台从没校验过。
     */
    private String categoryCodes;

    /**
     * 归档时间。<b>软删除标记</b> —— 有值即从运营端默认列表消失，业务数据全保留。
     *
     * <p>与 {@code status} <b>正交</b>：暂停的还在列表里等着被恢复，
     * 归档的从列表消失。挤进同一列的话，「暂停后归档」会丢掉其中一个状态。
     */
    private java.time.LocalDateTime archivedAt;
}
