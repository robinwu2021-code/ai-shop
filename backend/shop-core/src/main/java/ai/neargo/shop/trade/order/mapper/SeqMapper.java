package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.SysSequence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 订单域持久化：沿用原表、平台工厂及SQL语义。 */
public interface SeqMapper extends BaseMapper<SysSequence> {

    /** 种子兜底：迁移已插则无副作用。INSERT IGNORE 两端（MySQL/H2）都认 */
    @Insert("INSERT IGNORE INTO sys_sequence(seq_name, next_value) VALUES(#{name}, 0)")
    int touch(@Param("name") String name);

    /** 原子推进 step（单条 UPDATE 带行锁） */
    @Update("UPDATE sys_sequence SET next_value = next_value + #{step} WHERE seq_name = #{name}")
    int bump(@Param("name") String name, @Param("step") long step);

    /** 读回当前游标（必须与 bump 同事务，否则读到的可能是别人 bump 后的值） */
    @Select("SELECT next_value FROM sys_sequence WHERE seq_name = #{name}")
    Long peek(@Param("name") String name);

}
