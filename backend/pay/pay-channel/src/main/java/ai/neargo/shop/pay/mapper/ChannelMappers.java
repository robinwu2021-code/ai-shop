package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.entity.SysPayChannelMarket;
import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 通道主数据的 mapper。
 *
 * <h2>为什么放在 {@code ai.neargo.shop.pay.mapper} 而不是 pay.channel 下</h2>
 * 这个包名是<b>装配决定的，不是分类决定的</b>：
 * <ul>
 *   <li>主应用的 {@code @MapperScan} <b>刻意排除 {@code ai.neargo.shop.pay.*}</b> ——
 *       pay 走独立的 SqlSessionFactory 与事务管理器，
 *       目的是让跨域事务在物理上写不出来；</li>
 *   <li>而 {@code PayDataSourceConfig} 只扫 {@code ai.neargo.shop.pay.mapper}。</li>
 * </ul>
 *
 * <p>2026-09-01 第一次把它放在 {@code pay.channel.master} 下，结果是
 * <b>两边都扫不到</b>：被主应用排除、又不在 pay 的扫描包里。
 * 症状是整个 Spring 上下文起不来，一串 UnsatisfiedDependency 的最末端才是真因。
 *
 * <p>放这里还有第二层意思：通道主数据<b>跟着 pay 的数据源走</b>，
 * D2 拆库时它跟支付域一起搬 —— 而那正是「通道属性是支付域的知识」的物理表达。
 */
public final class ChannelMappers {

    private ChannelMappers() {
    }

    /** 通道主数据表 */
    public interface PayChannelMapper extends BaseMapper<SysPayChannel> {
    }

    /** 通道 × 市场（V295）。替掉 sys_pay_channel.markets 那列 JSON 文本 */
    public interface PayChannelMarketMapper extends BaseMapper<SysPayChannelMarket> {

        /**
         * <b>物理删除</b>，不走 BaseEntity 的逻辑删除。
         *
         * <p>这是一张纯关联表：一行的全部含义就是「这个通道在这个市场可用」，
         * 删掉之后没有任何东西需要追溯 —— 谁在什么时候改的，
         * 留痕在审计日志里，不在这张表的墓碑上。
         *
         * <p><b>而逻辑删除在这里会真的坏事</b>：唯一键是 (租户, 通道, 市场)，
         * 逻辑删除留下的 deleted=1 行仍然占着那个键。运营第二次改同一个市场时，
         * 「把它标成已删」会撞上上一次留下的墓碑 —— 保存直接报错，
         * 而报的是一个与「改市场」毫无关系的重复键。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM sys_pay_channel_market WHERE pay_channel = #{payChannel}")
        int deleteByChannel(@org.apache.ibatis.annotations.Param("payChannel") String payChannel);
    }

    /** 通道费率版本表（V274）。只增不改 */
    public interface PayChannelRateMapper extends BaseMapper<SysPayChannelRate> {
    }

    /** 渠道报文表（V286）。只增不改，按保留期清理 */
    public interface ChannelMessageMapper
            extends BaseMapper<StlChannelMessage> {
    }
}
