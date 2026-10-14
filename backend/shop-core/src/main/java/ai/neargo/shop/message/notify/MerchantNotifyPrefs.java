package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.entity.MchNotifyPref;
import ai.neargo.shop.message.entity.MsgSceneChannel;
import ai.neargo.shop.message.mapper.MessageMappers.MchNotifyPrefMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店自己的通知开关，与平台总闸**串联**
 * （TDD-来单四渠道与商家通知设置 §2.2）。
 *
 * <pre>
 *   平台 notify_scene_channel（运营配，总闸）
 *     × 门店 mch_notify_pref（店主配，分闸）
 *     = 这条通道发不发
 * </pre>
 *
 * <p><b>商家级只能更严</b>：平台关掉的，商家开着也不发。
 * 反过来会让运营的全局停发（比如某条通道出故障时）被商家的配置绕过。
 *
 * <p><b>缺行 = 开</b>。见 {@link MchNotifyPref} 的类注释 —— 反过来的话这张表一建，
 * 存量商家当天就一条来单提醒都收不到，而且没有任何报错。
 */
@Service
public class MerchantNotifyPrefs {

    private final MchNotifyPrefMapper mapper;
    private final SceneChannelRouting routing;

    public MerchantNotifyPrefs(MchNotifyPrefMapper mapper, SceneChannelRouting routing) {
        this.mapper = mapper;
        this.routing = routing;
    }

    /**
     * 这条通道对这家门店发不发 —— **调用方只该问这一个方法**，
     * 别自己先问 {@code routing} 再问这里，那样串联顺序会在各处分叉。
     *
     * @param channel {@link MchNotifyPref#SWITCHABLE} 里的一条。
     *                传 INAPP 进来恒 true（站内信不可关），不抛 ——
     *                调用方本来就不该问它，问了也不能让事实记录消失
     */
    public boolean on(String storeNo, String scene, String channel) {
        if (MsgSceneChannel.CH_INAPP.equals(channel)) {
            return true;
        }
        if (!routing.enabled(scene, MsgSceneChannel.AUD_B_STAFF, channel)) {
            return false;   // 平台总闸关着，不用再看商家
        }
        return merchantSwitch(storeNo, scene, channel);
    }

    /** 只看门店那一层（给设置页回显用 —— 页面要显示店主自己的选择，不是串联结果） */
    public boolean merchantSwitch(String storeNo, String scene, String channel) {
        if (storeNo == null || storeNo.isBlank()) {
            /*
             * 没有门店号可问 = 开。**这一条不是兜底，是正路**：
             * 售后与评价那两个场景的事件根本不带 store_no（只有 entity_no），
             * 它们走到这里就是这一支 —— 于是只受平台总闸管，与改造前完全一致。
             */
            return true;
        }
        MchNotifyPref row = DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<MchNotifyPref>lambdaQuery()
                        .eq(MchNotifyPref::getStoreNo, storeNo)
                        .eq(MchNotifyPref::getScene, scene)
                        .eq(MchNotifyPref::getChannel, channel)
                        .last("limit 1")));
        return row == null || Boolean.TRUE.equals(row.getEnabled());
    }

    /**
     * 设置页要显示的四个开关（商家自己那一层，按 {@link MchNotifyPref#SWITCHABLE} 的顺序）。
     *
     * <p><b>一次查完，不是四次</b>：设置页一进来就要四个值，
     * 逐个查是四条 SQL —— 接口预算是 200ms 量级，这种 N 次查询没有理由。
     */
    public Map<String, Boolean> switchesOf(String storeNo, String scene) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String ch : MchNotifyPref.SWITCHABLE) {
            out.put(ch, true);      // 先铺默认值 —— 缺行就是开
        }
        if (storeNo == null || storeNo.isBlank()) {
            return out;
        }
        List<MchNotifyPref> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<MchNotifyPref>lambdaQuery()
                        .eq(MchNotifyPref::getStoreNo, storeNo)
                        .eq(MchNotifyPref::getScene, scene)));
        for (MchNotifyPref r : rows) {
            if (out.containsKey(r.getChannel())) {
                out.put(r.getChannel(), Boolean.TRUE.equals(r.getEnabled()));
            }
        }
        return out;
    }

    /**
     * 店主改这家店的一个开关。幂等 upsert。
     *
     * <p><b>INAPP 直接拒</b>：站内信是事实记录，恒发不可关。
     * 守在这里而不是只守在前端 —— 端点被直接调也兜住。
     * 同理，不在 {@link MchNotifyPref#SWITCHABLE} 里的通道码一律拒，
     * 否则库里会长出一堆没人读的行，而「我明明关了」却毫无效果。
     */
    @Transactional
    public void set(String storeNo, String scene, String channel, boolean enabled, String operator) {
        if (storeNo == null || storeNo.isBlank()
                || scene == null || scene.isBlank()
                    || !MchNotifyPref.SWITCHABLE.contains(channel)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        DataScopeContext.executeWithoutScope(() -> {
            MchNotifyPref row = mapper.selectOne(Wrappers.<MchNotifyPref>lambdaQuery()
                    .eq(MchNotifyPref::getStoreNo, storeNo)
                    .eq(MchNotifyPref::getScene, scene)
                    .eq(MchNotifyPref::getChannel, channel)
                    .last("limit 1"));
            if (row == null) {
                row = new MchNotifyPref();
                row.setStoreNo(storeNo);
                row.setScene(scene);
                row.setChannel(channel);
                row.setEnabled(enabled);
                row.setUpdatedBy(operator);
                mapper.insert(row);
            } else {
                row.setEnabled(enabled);
                row.setUpdatedBy(operator);
                mapper.updateById(row);
            }
            return null;
        });
    }
}
