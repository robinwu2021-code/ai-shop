package ai.neargo.shop.fulfillment.service;

import ai.neargo.shop.fulfillment.dto.CarrierConfigVO;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;

import java.util.List;

/**
 * 平台侧物流（P-5.2）：快递运单与轨迹 · 运费模板与超区 · 第三方运力配置。
 *
 * <h2>一期做到哪里</h2>
 *
 * <p><b>不接任何承运商 API</b>（ADR-005 §5：一期只做快递 + 商家自送）。
 * 这一层做的是「存住 + 展示 + 校验」：运单号回填与换号留痕、运费模板落库、运力档案启停。
 * 真接回传要密钥托管、回调鉴权、重试与对账，是一个完整子系统，不是补几个端点。
 *
 * <p>校验逐条对齐 ops-web 的 mock（{@code lib/api/mocks/fulfillment.ts}）。
 * <b>后端不能比 mock 宽</b>：mock 上点不通的路径，指向真后端也该点不通 ——
 * 反过来才是「mock 比后端好看」那类缺陷的来源。
 */
public interface LogisticsService {

    // 运单列表与换单号已搬到物流模块（LogisticsAdminPort，TDD-物流模块 批 5）

    /** @param showArchived 为真时连归档的一起返回（G1：归档不是删除，得看得见） */
    List<FreightTemplateVO> freightTemplates(boolean showArchived);

    /** 新建 / 保存运费模板（含超区规则）。{@code templateNo} 为空即新建。 */
    FreightTemplateVO saveFreightTemplate(FreightTemplateCmd cmd, String operatorNo);

    /**
     * 归档模板（G1 软删除）。
     *
     * <p>硬删会把历史订单的运费依据一起抹掉 —— 之后谁也说不清那单当时为什么收了 8 元。
     * <b>默认模板归档不了</b>：归档之后新商家没有模板可用。
     */
    FreightTemplateVO archiveFreightTemplate(String templateNo, String operatorNo);

    FreightTemplateVO unarchiveFreightTemplate(String templateNo, String operatorNo);

    /** 运力档案，<b>按优先级升序</b> —— 页面上的顺序就是真实的选取顺序。 */
    List<CarrierConfigVO> carriers();

    /**
     * 保存一家运力的接入配置。
     *
     * <p><b>密钥不在这里配</b>：契约里只有 {@code apiKeyConfigured} 这个布尔，
     * 密钥本身不该出现在前端契约里，哪怕是脱敏的。
     */
    CarrierConfigVO saveCarrier(String carrier, String name, int priority,
                                String pickupCutoff, int slaHours, String operatorNo);

    /**
     * 启停一家运力。三条闸，每一条防的都是<b>「订单发不出去」</b>而不是「显示不对」：
     * 没配密钥不能启用、还有在途单不能停用、不能停掉最后一家启用的。
     */
    CarrierConfigVO setCarrierEnabled(String carrier, boolean enabled, String operatorNo);

    /**
     * 轮询在途运单的<b>真实承运商轨迹</b>（TDD-圆通物流直连 Y3）。
     *
     * <p>这里补上的正是 {@link LogisticsServiceImpl} 头部与 ADR-005 §5 当初推迟的那块 ——
     * 「一期不接承运商 API」。接法不改前面的任何写路径：
     * 扫在途 {@code ful_shipment}（含疑难件，它不是终态）、按<b>门店</b>路由到 provider
     * （默认圆通，缺凭据/查不到就跳过本单，不编造推进）、把真实节点<b>追加</b>进
     * {@code ful_shipment_trace}（按时刻+文案去重）、据签收推进运单状态。已签收的移出轮询。
     *
     * @param limit 一轮最多刷多少单，按「最久没刷的优先」
     */
    TraceRefreshResult refreshInTransitTraces(int limit);

    /**
     * @param scanned   本轮扫到的在途单数
     * @param queried   provider 真查到轨迹的单数（缺凭据时恒 0 —— 这<b>不是</b>「跑成功了」）
     * @param appended  追加了新轨迹节点的单数
     * @param advanced  运单状态被推进的单数
     * @param delivered 其中推进到「已签收」的单数（移出轮询）
     */
    record TraceRefreshResult(int scanned, int queried, int appended, int advanced, int delivered) {
    }

    /**
     * 等待备微信展示载荷的在途运单：还没拿到 token、且距上次备超过 TTL（躲微信 trace_waybill 的调用上限）。
     * 微信那条的触发（组装 openid/trans_id/商品、调 trace_waybill）在 shop-app/paybridge，
     * 那里才拿得到支付域的付款人 openid；这里只把「该备哪些单」挑出来。
     *
     * @param limit 一轮最多备多少单
     */
    List<WxBindTarget> wxBindTargets(int limit);

    /**
     * 备微信展示载荷的结果写回这一单。
     *
     * <p><b>成功</b>（{@code token} 非空）：置 {@code display_channel=wx-plugin}、{@code display_token}，
     * 清掉上次的失败原因；小程序据此打开微信全屏物流页。
     * <b>没备成</b>（{@code token} 空）：只记 {@code display_fail_reason} 给运营看，渠道保持空（读路径落自建地图）。
     * 两种都更新 {@code display_prepared_at} —— 这是 TTL 判据，防止反复 trace_waybill 打穿配额。
     */
    void applyWxDisplay(String shipmentNo, String channel, String token, String failReason);

    /** 待备微信载荷的运单。storeNo 展示侧用不到，故不带。 */
    record WxBindTarget(String shipmentNo, String subOrderNo, String carrier, String waybillNo) {
    }

    /**
     * 已签收、可以提醒买家确认收货的运单（批 A）。
     *
     * <p>只挑 {@code DELIVERED} 且记下了 {@code signed_at} 的 —— 没有签收时间就没法调微信
     * （{@code received_time} 必填且要晚于发货时间）。<b>幂等不在这一层</b>：
     * 微信「每单一次」是按支付单算的，而一个支付单可能对应多张子单，
     * 所以「提醒过没有」由 shop-app/paybridge 按支付单判，这里只负责「哪些运单签收了」。
     *
     * @param limit 一轮最多挑多少单
     */
    List<SignedShipment> signedShipments(int limit);

    /** 已签收的运单。{@code signedAt} 毫秒 */
    record SignedShipment(String shipmentNo, String subOrderNo, long signedAt) {
    }

    /**
     * 保存运费模板的入参。
     *
     * @param outOfRange 超区规则。同一区域只能有一条 —— 配两条时命中哪条取决于顺序
     */
    record FreightTemplateCmd(String templateNo, String name,
                              int firstWeightGram, long firstFee,
                              int addWeightGram, long addFee,
                              long freeThreshold, boolean isDefault,
                              List<FreightTemplateVO.OutOfRangeVO> outOfRange) {
    }
}
