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
 * <p><b>无行 = 不限市场。</b>沿用旧语义 —— 改成「无行 = 都不可用」
 * 会让 V288 那个刻意留空的 TEST 通道一夜消失，而它留空正是为了
 * 能在任何市场的链路上验证。
 */
@Getter
@Setter
@TableName("sys_pay_channel_market")
public class SysPayChannelMarket extends BaseEntity {

    private String payChannel;
    private String market;

}
