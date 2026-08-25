package ai.neargo.shop.common;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务键生成：{@code 前缀 + yyyyMMddHHmmss + 4 位序 + 3 位随机}。
 *
 * <p>为什么不用 UUID：业务键会被人念（客服问「您的订单号是」）、会被打印在取货码小票上、
 * 会按时间排序做运营查询。UUID 三样都不占。
 *
 * <p>为什么带随机位：单机自增序在多实例部署下会撞号；随机位把碰撞概率压到可忽略，
 * 真撞了也有唯一索引兜底（业务键在库里一律建 UNIQUE）。
 */
public final class BizKey {

    /** 前缀是语义的一部分：看到 {@code SUB} 就知道这是子订单，不用去查表。 */
    public static final String ORDER = "SO";
    public static final String SUB_ORDER = "SUB";
    public static final String AFTER_SALE = "AS";
    public static final String USER = "U";
    /**
     * 平台人档（{@code usr_person}）。
     *
     * <p><b>与 {@link #USER} 分开是有意的</b>：账号要注册才有，人档不用 ——
     * 商家录进来的手机号，本人可能还没在平台出现过。会员挂在人档上，不挂账号上。
     */
    public static final String PERSON = "PS";
    /** 会员：一个人 × 一家主体的关系。与 PERSON 分开 —— 一份人档可以是好几家店的会员 */
    public static final String MEMBER = "MB";
    /** 会员来源明细。每一次来源一行 */
    public static final String MEMBER_SOURCE = "MS";
    /** 会员标签。号不可变，名字可改 —— 关系表存的是它 */
    public static final String MEMBER_TAG = "MT";
    /** 人群：一组筛选条件。发券、活动受众、触达共用它，避免同一群人算出三个数 */
    public static final String MEMBER_SEGMENT = "SG";
    public static final String ADDRESS = "AD";
    public static final String TICKET = "TK";
    public static final String MESSAGE = "MSG";
    public static final String MERCHANT = "M";
    public static final String MERCHANT_APPLY = "MA";
    public static final String STAFF = "ST";
    public static final String PICKUP_POINT = "PP";
    /** 社区（小区/网格）。运营开的点，或商家提报审过之后建出来的 */
    public static final String COMMUNITY = "C";
    /** 商家提报的新社区（ADR-013 阶段三） */
    public static final String COMMUNITY_APPLY = "CA";
    public static final String GOODS = "G";
    public static final String SKU = "SK";
    public static final String SPEC_TEMPLATE = "SPT";
    /** 平台标准品（TDD-标准品库）。种子用的是 STD1xxx/STD2xxx，新建走这个前缀 */
    public static final String SPU_STD = "STD";
    /** 主题分类（陈列）。与活动的 CP 分开：摆到一起 ≠ 降价 */
    public static final String TOPIC = "TP";
    public static final String STORE = "ST";
    public static final String MERCHANT_STAFF = "SF";
    public static final String GROUP_BUY = "GB";
    public static final String GROUP_REQUEST = "GR";
    public static final String QUOTE = "Q";
    public static final String COUPON = "CP";
    /** 营销活动。与 COUPON 分开：一个活动可以发出成千上万张券，两者不是一回事 */
    public static final String CAMPAIGN = "CM";
    public static final String REVIEW = "RV";
    public static final String APPEAL = "AP";
    public static final String SETTLE_BILL = "STL";
    /** 开票申请（平台开给消费者）。与 STL 的采购发票是两回事：那是进项，这是销项 */
    public static final String INVOICE_REQUEST = "INV";
    /** 费率规则版本 */
    public static final String FEE_RULE = "FR";
    public static final String EVENT = "EVT";
    /** 短信/邮件发送记录 */
    public static final String NOTIFY_LOG = "NL";
    /** 平台营销广播推送任务（N6） */
    public static final String PUSH_TASK = "NPT";
    /**
     * 类目。运营新建的类目走这个前缀；种子里那批（CAT100…CAT400）是手写的主数据，
     * 编号与 ops-web 的 mock 对齐，联调时不用在两套编号之间换算。
     */
    public static final String CATEGORY = "CAT";
    /** 违规处置记录 */
    public static final String VIOLATION = "VL";
    /** 店招/公告人审单 */
    public static final String STORE_AUDIT = "SA";
    /** 商家的一条地理覆盖项（ADR-013） */
    public static final String SERVICE_AREA = "SVA";

    /** 收款商户号业务键。**不是二级商户号本身** —— 那个由通道给，存在 sub_mchid */
    public static final String PAY_MERCHANT = "PM";
    /** 对账差异单 */
    public static final String RECON_DIFF = "RD";
    /** 保证金流水 */
    public static final String DEPOSIT_TXN = "DP";
    /** 积分流水 */
    public static final String POINTS_LEDGER = "PL";
    /** 积分资金池流水 */
    public static final String POINTS_POOL = "PP";
    /** 榜单 */
    public static final String RANKING = "RK";
    /** 运营素材 */
    public static final String MATERIAL = "MT";
    /** 员工与授权的操作日志（B-11.10.3） */
    public static final String STAFF_LOG = "SL";
    /** 商家自定义角色（V71）。预置角色的码是 OWNER/MANAGER… 这类词，不走这里 */
    public static final String MERCHANT_ROLE = "R";

    /** 到货批次（P-5.1.1）。一个自提点一天一批，配车信息挂在它上面 */
    public static final String ARRIVAL_BATCH = "BAT";
    /**
     * 快递运单记录（P-5.2.1）。<b>不是快递单号</b> —— 那个由承运商给，存在 waybill_no。
     * 分成两个键是因为换单号时运单记录必须还是同一条，否则轨迹会断
     */
    public static final String SHIPMENT = "SH";
    /** 运费模板（P-5.2.3） */
    public static final String FREIGHT_TEMPLATE = "FT";

    /**
     * 风险事件（P-16.2）。<b>不复用 {@link #EVENT}</b> —— 那个是 Outbox 的领域事件，
     * 两者是完全不同的东西，共用前缀之后「EVT... 是哪种事件」要靠猜
     */
    public static final String RISK_EVENT = "RE";
    /** 黑名单记录（P-16.2.4） */
    public static final String BLACKLIST = "BL";
    /** 归因链路（P-9.1.3）。运营端按它检索一次归因判定 */
    public static final String ATTRIBUTION_TRACE = "AT";
    /** 裂变活动（P-9.2.1） */
    public static final String FISSION = "FS";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BizKey() {
    }

    public static String next(String prefix) {
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        int rand = RANDOM.nextInt(1000);
        return "%s%s%04d%03d".formatted(prefix, LocalDateTime.now().format(FMT), seq, rand);
    }
}
