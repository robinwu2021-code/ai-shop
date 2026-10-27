package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 通道 × 市场（V295）：这个通道在哪些市场可用。
 *
 * <h2>它替掉的是一列 JSON 文本</h2>
 * 此前存在 {@code sys_pay_channel.markets} 里（如 {@code ["CN"]}），
 * 判断要靠一段正则先把方言差异抹平 —— 同一句种子 SQL
 * 在 MariaDB 存下 {@code ["CN"]}、在 H2 存下 {@code [\"CN\"]}，
 * <b>字节不一样</b>。这里 market 是一个普通字符串列，两边一模一样。
 *
 * <p>更要紧的是那列文本做不到的三件事：市场码写错了没有任何东西会发现、
 * 反向查「TW 有哪些通道」要全表扫加逐行解析、
 * 以及费率按市场分档时<b>没法 JOIN 一段 JSON</b>。
 *
 * <p><b>无行 = 不可用</b>（db/pay V12 起，TDD-支付服务-W2渠道层补完 S8）。V295 时沿用的是「无行 = 不限市场」——
 * 那时改语义会让刻意留空的 TEST 通道一夜消失；V12 先把它的「不限」搬成每个市场一行，再翻语义。
 * 不翻的话，一个没配市场的新渠道（蓝新 / LINE Pay / PayBy）会在所有市场出现，包括大陆。
 */
@Getter
@Setter
@TableName("sys_pay_channel_market")
public class SysPayChannelMarket extends BaseEntity {

    private String payChannel;
    private String market;
    /** V4 加：ENABLED / DISABLED。显式停用的行不算可用；插入时不填由列默认 ENABLED */
    private String status;

}
