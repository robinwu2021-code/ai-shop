package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
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

    /** 通道费率版本表（V274）。只增不改 */
    public interface PayChannelRateMapper extends BaseMapper<SysPayChannelRate> {
    }

    /** 渠道报文表（V286）。只增不改，按保留期清理 */
    public interface ChannelMessageMapper
            extends BaseMapper<StlChannelMessage> {
    }
}
