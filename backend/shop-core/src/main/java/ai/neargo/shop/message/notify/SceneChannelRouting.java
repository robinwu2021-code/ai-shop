package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.entity.MsgSceneChannel;
import ai.neargo.shop.message.mapper.MessageMappers.SceneChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 场景×通道路由（设计：多渠道推送与运营端触达配置 · 需求 1）。
 *
 * <p>回答 {@link NotificationConsumer} 的两个问题：这个场景发给这个受众时，
 * <b>某条外发通道开没开</b>，以及 <b>推送该用什么级别</b>。规则来自 {@code msg_scene_channel}，
 * 运营可改，不再硬编码。
 *
 * <p><b>站内信不问这里</b>：INAPP 是事实记录，{@link NotificationConsumer} 里硬编码必发。
 * 这里只管「加速通道」（WXSUB / PUSH）开不开。
 *
 * <p><b>没配到就当关</b>：一个没落种子的新场景，宁可只发站内信也不擅自外发打扰用户；
 * 种子齐不齐由 {@code SceneChannelSeedTest} 守卫在测试期兜住，不靠运行期猜。
 */
@Service
public class SceneChannelRouting {

    private final SceneChannelMapper mapper;

    public SceneChannelRouting(SceneChannelMapper mapper) {
        this.mapper = mapper;
    }

    /** 某场景×受众×通道是否开启。查不到 = 关（保守，不擅自外发）。 */
    public boolean enabled(String sceneCode, String audience, String channel) {
        MsgSceneChannel row = find(sceneCode, audience, channel);
        return row != null && Boolean.TRUE.equals(row.getEnabled());
    }

    /**
     * 该场景×受众的推送级别（NORMAL / RING）。没配到回落 NORMAL ——
     * 「响铃」是要显式配的强打扰，缺配时不该默默把用户吵醒。
     */
    public String pushLevel(String sceneCode, String audience) {
        MsgSceneChannel row = find(sceneCode, audience, MsgSceneChannel.CH_PUSH);
        return row != null && MsgSceneChannel.LEVEL_RING.equals(row.getPushLevel())
                ? MsgSceneChannel.LEVEL_RING : MsgSceneChannel.LEVEL_NORMAL;
    }

    private MsgSceneChannel find(String sceneCode, String audience, String channel) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<MsgSceneChannel>lambdaQuery()
                        .eq(MsgSceneChannel::getSceneCode, sceneCode)
                        .eq(MsgSceneChannel::getAudience, audience)
                        .eq(MsgSceneChannel::getChannel, channel)
                        .last("limit 1")));
    }

    // ------------------------------------------------------------ 运营端

    /** 整张矩阵，按 场景→受众→通道 稳定排序，供运营勾选面板渲染。 */
    public List<MsgSceneChannel> list() {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<MsgSceneChannel>lambdaQuery()
                        .orderByAsc(MsgSceneChannel::getSceneCode)
                        .orderByAsc(MsgSceneChannel::getAudience)
                        .orderByAsc(MsgSceneChannel::getChannel)));
    }

    /**
     * 运营切换某格开关。
     *
     * <p><b>INAPP 拒绝改</b>：站内信是事实记录，界面上就锁定，后端再兜一道 ——
     * 前端被绕过也不能把必达记录关掉。
     */
    public MsgSceneChannel setEnabled(String sceneCode, String audience, String channel,
                                      boolean on, String operatorNo) {
        if (MsgSceneChannel.CH_INAPP.equals(channel)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MsgSceneChannel row = find(sceneCode, audience, channel);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        row.setEnabled(on);
        row.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
        return row;
    }
}
