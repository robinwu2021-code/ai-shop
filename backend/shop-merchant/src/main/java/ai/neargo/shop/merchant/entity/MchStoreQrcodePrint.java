package ai.neargo.shop.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 店铺码印刷量登记（append-only 台账）。
 *
 * <p><b>印了多少张贴纸是线下事实</b>，系统不可能自动知道 —— 所以它由运营录入，
 * 而不是按「导出次数 × 每次张数」估。估出来的是一个看起来很精确的编造值，
 * 而看板上没人分辨得出它是估的。
 *
 * <p>一次登记一行，累计由行相加：印多了要冲减就补一行负数。
 * 存一个 total 列的话，「当初到底印了多少」会被就地改掉 —— 而那正是对账要问的。
 */
@Getter
@Setter
@TableName("mch_store_qrcode_print")
public class MchStoreQrcodePrint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String printNo;

    /** 商家主体。店铺码一主体一码，印刷量因此也挂主体 */
    private String entityNo;

    /** 本次印量，<b>有符号</b>：冲减补负数行，不改历史行 */
    private Integer qty;

    /** 贴纸尺寸。属于<b>这一次印刷</b>，不是门店的固有属性 */
    private String size;

    private String remark;

    /** 谁登记的 —— 这是一笔会进成本对账的数 */
    private String operatorNo;

    private Long at;

    private String tenantNo;
    private LocalDateTime createdAt;
}
