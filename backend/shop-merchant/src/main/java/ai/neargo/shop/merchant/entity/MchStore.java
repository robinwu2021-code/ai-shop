package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店（V44 起由「主体的门面」升级为门店实体，表名 mch_store）。与 {@link MchEntity}（主体）分开：
 * 公告今天写明天改，而主体表被下单链路读 —— 改公告不该去抢那一行的锁。
 *
 * <p>经营范围（{@code serviceScope} / 覆盖社区）刻意<b>留在主体上</b>：
 * 那决定的是「这家店的货谁能看到」（ADR-009），是主体属性而不是门面装修。
 */
@Getter
@Setter
@TableName("mch_store")
public class MchStore extends BaseEntity {

    /**
     * 自营：平台是销售主体。没有 EDI 时只能自营，故为默认值。
     *
     * <p>取值域的**唯一定义处**在 {@code MerchantQueryPort}——跨域调用方
     * （如 settle 生成结算单时）不能依赖商家域，所以放在契约上。这里只是转引用，
     * 不重新写一份字面量：两处各写一份，改一处就会静默分岔。
     */
    public static final String SELF_OPERATED = ai.neargo.shop.spi.user.MerchantQueryPort.MODE_SELF_OPERATED;
    /** 第三方：商家是销售主体，平台收佣金。需要 EDI 与收付通。 */
    public static final String THIRD_PARTY = ai.neargo.shop.spi.user.MerchantQueryPort.MODE_THIRD_PARTY;

    /** ACTIVE / SUSPENDED / READONLY（Plan 降级：不接新单，但未完成的单照常核销）。 */
    public static final String ACTIVE = "ACTIVE";
    public static final String READONLY = "READONLY";
    /**
     * 平台强制下线（V96 起收编 DDL 里既有的取值）。与 {@link #READONLY}（商家自助停用/Plan 降级）
     * 必须是两个值：处置中的店商家不能自己点两下启用就解除 —— 解除只能由平台做。
     */
    public static final String SUSPENDED = "SUSPENDED";

    private String entityNo;

    /**
     * 经营模式：{@link #SELF_OPERATED} / {@link #THIRD_PARTY}。
     *
     * <p>挂在门店而不是主体上：同一主体下旗舰店做自营、加盟店做第三方是常见形态，
     * 而选择依据（品类、供货方资质、履约控制）本来就是按店不同的。
     *
     * <p><b>与「分账时机」是两个正交的轴</b>，不要合并成一个枚举——
     * 合并之后「自营 + 直连分账」这种非法组合在类型上就是可表达的。
     */
    private String businessMode;

    /** 门店业务键。 */
    private String storeNo;

    /** 门店名，<b>可与主体名不同</b>（「张记粮油·文三路店」）。 */
    private String name;

    /** 默认门店。一主体<b>恰好一个</b>，删不掉 —— 它是单店商家的全部。 */
    private Boolean isDefault;

    /**
     * 这家店自己的店铺码（V298）。
     *
     * <p>默认店回填自 {@code mch_entity.store_code} —— <b>已经印出去的贴纸因此不作废</b>，
     * 只是从此归到默认店名下，与今天的口径一致（今天所有分店共用一个码，本来也只能算到主体）。
     *
     * <p><b>空 = 这家分店还没发过码</b>，不是「码是空串」。分店要各算各的获客，
     * 得先发码再印。
     */
    private String storeCode;

    /**
     * 这家店的小程序码 PNG（base64，不含 {@code data:} 前缀）。
     *
     * <p><b>生成一次就复用</b>：微信永久码每个 appid 总量有限，码下沉到门店之后
     * 每家分店各要一张 —— 现调现用会把额度耗光，而耗光之后新商家再也拿不到码。
     */
    private String acodeBase64;

    private String status;

    /**
     * 因**套餐降级**被压为只读（V150）。与商家自己停用的 {@link #READONLY} 状态相同，
     * 靠这一列区分「谁压的」。
     *
     * <p><b>补缴恢复时只回这一批</b>：降级压了 2 家、商家又自己停了 1 家时，
     * 三家的 status 一模一样 —— 全恢复等于平台替商家做了开店决定，
     * 全不恢复等于他买的东西没还给他。
     *
     * <p>与 {@code prd_store_goods.platform_suspended} 是同一个形状：
     * 凡是「系统压下去、以后要按原样还回来」的地方，都必须留标记说明是谁压的。
     */
    private Boolean planSuspended;

    /**
     * 这家店用哪个收款商户号（{@code mch_payment_merchant}）。
     *
     * <p><b>为空 = 用主体的默认商户号</b> —— 单通道时永远为空，行为与今天一致。
     *
     * <p>它是<b>关联而不是归属</b>：门店归属主体，商户号也归属主体，
     * 两者之间可以换。如果门店归属商户号，换收款方就等于换店 ——
     * 评价清零、老客断链，而它本质上只是换了个收钱的口子。
     */
    private String payMerchantNo;

    /** 最近一次切换收款商户号的时间。换商户号会改变钱的去向，要能追。 */
    private Long paymentChangedAt;
    private String announcement;

    /**
     * 公告失效时刻（epoch 毫秒）。<b>空 = 长期有效</b>。
     *
     * <p>过期**由读时判断**（见 {@link #effectiveAnnouncement()}），不跑定时任务：
     * 定时会在重启、时区、漏跑上出问题，而这件事经不起漏一次 ——
     * 「昨天到货」挂一周比没有公告更伤信任，买家是照着它来的。
     */
    private Long announcementUntil;

    /**
     * 公告最后一次发布的时刻（epoch 毫秒）。
     *
     * <p><b>只在正文真的变了的时候写</b>：改有效期、改营业时间、换收款号都不动它。
     * 用 {@code updated_at} 代替的话，一句三周前的公告会因为店主今天改了营业时间
     * 而显示成「刚刚更新」—— 那比不显示时间更糟。
     */
    private Long announcementAt;

    /**
     * 最近用过的公告，JSON 数组最多 5 条，按最近使用排序。
     *
     * <p>店主的公告是在几句话之间轮换（「今天到货」「今日售罄」「下午半价」），
     * 不是每次都写新的 —— 存下来点一下即换，比每次重打一遍快一个量级。
     */
    private String announcementRecent;

    /**
     * 此刻**对外该显示的**公告：过期即空。
     *
     * <p>两条读路径都要走它 —— B 端的 `profile()` 与 C 端的 `storeFront()`。
     * 只在一处判过期的话，另一处会继续显示已经过期的那条，
     * 而这种不一致最难被发现：商家自己看是空的，买家看到的却是昨天的货。
     */
    public String effectiveAnnouncement() {
        if (announcement == null || announcement.isBlank()) {
            return "";
        }
        if (announcementUntil != null && announcementUntil < System.currentTimeMillis()) {
            return "";
        }
        return announcement;
    }
    private String openHours;
    private String address;

    /**
     * 门牌号 / 楼栋，商家手填（「3 栋 2 单元 501」）。
     *
     * <p><b>与 {@link #address} 分开</b>：那条来自地图选点，只到小区或路名；
     * 买家照着找门缺的就是这一截。合成一格的话，商家补完门牌号再点一次选点，
     * 整条被覆盖 —— 他补的那截无声消失，而地址看着还是对的。
     */
    @com.baomidou.mybatisplus.annotation.TableField(
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String addressDetail;

    /** JSON 数组：主推商品 goods_no，<b>有序</b>。顺序是门面的编排，不是商品自身属性。 */
    private String featured;

    /**
     * 配送半径（米）。默认 3km。
     *
     * <p>挂门店不挂主体：半径是从**这家店门口**量出去的。挂主体的话，
     * 第二家店一开，两边的覆盖范围就都是错的 —— 而错法是「明明送得到却下不了单」，
     * 用户不会来反馈，只会换一家。
     */
    private Integer deliveryRadiusM;

    /** 起送价（分）。0 = 不设门槛。 */
    private Long deliveryMinOrderMinor;

    /** 配送费（分）。0 = 免费送。 */
    private Long deliveryFeeMinor;

    /** 免配送费门槛（分）。0 = 不免。 */
    private Long deliveryFreeThresholdMinor;

    /**
     * 门店评分（V155，ADR-011 决定表第 3 行）。**×10 存整数**，与主体那几列同名同口径。
     *
     * <p>为什么门店要有自己的分：顾客评的是「楼下那家」。三家店混成一个分，
     * 好店会被差店拖下去，而新开的分店会凭空继承老店的分。
     *
     * <p>{@code ratingCount = 0} 是「暂无评价」，不是 0 分 ——
     * 新店与老评价还没重算过的店都是这个形状，页面要按条数判空而不是按分值。
     */
    private Integer rating;
    private Integer ratingCount;
    private Integer scoreGoods;
    private Integer scoreService;
    private Integer scoreSpeed;

    /** 门店坐标（gcj02，E6，V190）。地理编码或定位回填；候选取货点按它排距离 */
    private Integer latE6;
    private Integer lngE6;
    /** 地理编码给的区县码（国标 6 位），与 sys_region 同口径 */
    private String adcode;
    /** 地址经地理编码校验并标准化（V190）。没配地图密钥时保持 0，不拦保存 */
    private Boolean addressVerified;

    /**
     * 这家店是否接受<b>线下（当面）收款</b>。支付方式四层判定的第 ③ 层。
     *
     * <p><b>默认关</b>（与商品侧 pay_modes 的「默认放行」不同）：
     * 它是「这家店愿不愿意」，属于商家的经营决定，不该由平台替他打开。
     *
     * <p>资质挂在<b>主体</b>上（{@code mch_qualification.entity_no}），
     * 而这个开关在<b>门店</b>上 —— 一家主体下三家分店共用同一张证，
     * 但可以只有临街那家开线下收款。<b>证是主体的，店是主体开的。</b>
     */
    private Integer offlinePayEnabled;

    /**
     * 货到付款（商家自送 + 线下付）。<b>单独一个开关，不跟着上面那个一起开。</b>
     *
     * <p>它是整张「支付方式 × 履约方式」组合表里<b>风险最高的一格</b> ——
     * 拒收、跑单，损失全在商家。所以要商家在承担得起的时候自己打开。
     */
    private Integer codEnabled;
}
