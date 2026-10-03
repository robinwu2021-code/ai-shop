package ai.neargo.shop.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Outbox 是基础设施表，各域共写，不参与模块归属划分。 */
public interface SysOutboxMapper extends BaseMapper<SysOutbox> {
}
