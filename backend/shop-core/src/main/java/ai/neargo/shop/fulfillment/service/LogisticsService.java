package ai.neargo.shop.fulfillment.service;

import ai.neargo.shop.fulfillment.dto.CarrierConfigVO;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;
import ai.neargo.shop.fulfillment.dto.ShipmentVO;

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

    /**
     * 快递运单列表（P-5.2.1）。
     *
     * <p><b>读时补齐</b>：快递履约且已回填快递单号的子单，若还没有运单记录就地建一条，
     * 承运商取当时优先级最高的启用运力。没回填单号的不建 ——
     * 那种单还没发货，建出来是一条永远没有轨迹的空记录。
     */
    List<ShipmentVO> shipments(String status, String carrier, String keyword);

    /**
     * 换运单号。运营在这一页唯一能做的写动作。
     *
     * <p>三条闸：<b>已签收的不许改</b>（等于把一条已完成的轨迹指向别处）、
     * <b>同承运商下不许重号</b>（两单轨迹会搅在一起）、<b>原因必填</b>
     * （之后对不上时这是唯一线索）。换号本身会写进轨迹。
     */
    ShipmentVO updateWaybill(String shipmentNo, String waybillNo, String reason, String operatorNo);

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
