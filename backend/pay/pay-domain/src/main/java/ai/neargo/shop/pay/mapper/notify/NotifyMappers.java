package ai.neargo.shop.pay.mapper.notify;

import ai.neargo.shop.pay.notify.entity.PayBizNotify;
import ai.neargo.shop.pay.notify.entity.PayBizNotifyAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 答复记录的 mapper（db/pay V5 · TDD-支付服务-I5接入层 S6） */
public final class NotifyMappers {

    private NotifyMappers() {
    }

    public interface PayBizNotifyMapper extends BaseMapper<PayBizNotify> {

        /**
         * 抢投递权：把下次时间推到租约末尾。多实例同时扫到同一行时只有一家拿到（版本号兜底），
         * 投递中途进程死了，租约到期后别的实例会再投 —— 业务方按 eventId 去重。
         */
        @Update("UPDATE pay_biz_notify SET next_at = #{leaseUntil}, version = version + 1, updated_at = #{now}"
                + " WHERE id = #{id} AND version = #{version} AND status IN ('PENDING','FAILED') AND deleted = 0")
        int claim(@Param("id") Long id, @Param("version") Long version, @Param("leaseUntil") LocalDateTime leaseUntil,
                  @Param("now") LocalDateTime now);
    }

    public interface PayBizNotifyAttemptMapper extends BaseMapper<PayBizNotifyAttempt> {
    }
}
