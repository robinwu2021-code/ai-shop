package ai.neargo.shop.platform;

import ai.neargo.shop.platform.dto.MasterDataVO;

import java.util.List;

/**
 * 主数据 —— 三端共用的取值域（行业 / 主体类型 / 支付渠道）。
 *
 * <p><b>为什么要有这一层</b>：这三样东西的取值此前在四个地方各存了一份
 * （shared 类型、三端各自的常量、后端硬编码），而它们的<b>规则来自通道</b>、
 * 会随对方调整而变。端上写死一份的直接后果是：微信放开了某个行业的小微，
 * 我们要发三个端的版本才能让商家选得到。
 *
 * <p>与配置中心的区别：这里只下发<b>取值域与展示名</b>，
 * 不下发密钥、不下发平台资金账户 —— 那些即使只读也不该出现在端上。
 */
public interface MasterDataService {

    /**
     * 全量快照。<b>只含启用的</b> —— 停用的行业/主体不该出现在入驻表单里。
     *
     * <p>运营侧要看停用的走 {@code /ops/industries}（那边是管理视图，含停用）。
     */
    MasterDataVO snapshot();

    /**
     * 该主体类型是否需要营业执照。入驻表单据此决定下一步显示什么。
     *
     * <p>兜底 true：查不到的主体一律按「要执照」处理 —— 少要一次执照的代价是
     * 一个不该通过的商家过了审，多要一次只是麻烦。
     */
    boolean needLicense(String subjectType);

    /** 该主体是否受行业白名单限制（仅小微）。查不到按 false —— 未知不该凭空加限制。 */
    boolean industryGated(String subjectType);

    /**
     * 把端上/存量的旧主体取值翻译成权威码。
     *
     * <p>存量数据里是 {@code PERSONAL/INDIVIDUAL_BIZ/COMPANY}，
     * 通道要的是 {@code MICRO/INDIVIDUAL/ENTERPRISE}。这个映射<b>只此一份</b> ——
     * 此前它在代码里出现过三次，各写各的，判错一次商家就是进件被拒。
     *
     * @return 传入的已经是权威码时原样返回；认不出来时返回 null
     */
    String canonicalSubject(String anySubject);

    /** 该主体的结算账户形态：PERSONAL_OPENID（打到个人）/ MERCHANT_ID（打到对公）。查不到返回 null。 */
    String settleAccountType(String subjectType);

    /** 支付通道展示名。查不到返回通道码本身 —— 页面宁可显示 WECHAT 也不要显示空白 */
    String channelName(String payChannel);

    /** 启用中的主体类型码。 */
    List<String> enabledSubjects();
}
