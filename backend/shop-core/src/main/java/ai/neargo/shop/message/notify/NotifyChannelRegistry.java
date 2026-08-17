package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 渠道注册表（设计：触达推送中台-模块抽象 · N2）。
 *
 * <p>回答「平台有哪些触达渠道、各自什么接入范围、开没开、什么状态」。渠道是一等实体，
 * 存在 {@code notify_channel}；启停是**软开关**（运营改 {@code enabled}，即时生效不重启）。
 *
 * <p><b>状态读时派生，不落库</b>：{@link #statusOf} 综合「接入范围 + 启停 + 凭据齐没齐 + 桩」
 * 现算 —— 落列必与现实分叉（配了密钥却显示 UNCONFIGURED，或反之）。凭据/桩事实问
 * {@link NotifyChannelService}，那是唯一的环境变量读取点，不在这里再读一遍。
 *
 * <p><b>本类暂居 message 域</b>：与它唯一的消费者 {@link NotifyChannelService} 同处；
 * 触达服务层整体迁入 shop-notify 是 N3 的事，届时随之搬走。
 */
@Service
public class NotifyChannelRegistry {

    private final NotifyChannelMapper mapper;
    private final NotifyChannelService channelService;
    private final PlatformChannelCredentials creds;

    public NotifyChannelRegistry(NotifyChannelMapper mapper, NotifyChannelService channelService,
                                 PlatformChannelCredentials creds) {
        this.mapper = mapper;
        this.channelService = channelService;
        this.creds = creds;
    }

    /**
     * 该渠道**还缺哪些平台凭证**（环境变量名），供运营端直接指出「要开它还差什么」。
     * 只对平台接入报：商家接入走加密密文、测试接入走桩，都不看 env 凭证。
     */
    public java.util.List<String> missingCreds(NotifyChannel ch) {
        if (!NotifyChannel.SCOPE_PLATFORM.equals(ch.getScope())) {
            return java.util.List.of();
        }
        return creds.missing(ch.getChannelType(), ch.getProvider());
    }

    /** 全部渠道，按 类型→供应商→接入范围 稳定排序。 */
    public List<NotifyChannel> list() {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<NotifyChannel>lambdaQuery()
                        .orderByAsc(NotifyChannel::getChannelType)
                        .orderByAsc(NotifyChannel::getProvider)
                        .orderByAsc(NotifyChannel::getScope)));
    }

    public NotifyChannel byNo(String channelNo) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<NotifyChannel>lambdaQuery()
                        .eq(NotifyChannel::getChannelNo, channelNo).last("limit 1")));
    }

    /**
     * 派生状态：
     * <ul>
     *   <li>TEST 接入 → 恒 {@code STUB}（它就是桩）；</li>
     *   <li>{@code enabled=0} → {@code DISABLED}（运营主动关，软开关优先）；</li>
     *   <li>该(类型,供应商)走桩 → {@code STUB}；</li>
     *   <li>必需凭据缺 → {@code UNCONFIGURED}；</li>
     *   <li>否则 → {@code READY}。</li>
     * </ul>
     * DEGRADED（体检失败）留 N3/N4 接体检历史后产出，本期不产生。
     */
    public String statusOf(NotifyChannel ch) {
        if (NotifyChannel.SCOPE_TEST.equals(ch.getScope())) {
            return NotifyChannel.STATUS_STUB;
        }
        if (!Boolean.TRUE.equals(ch.getEnabled())) {
            return NotifyChannel.STATUS_DISABLED;
        }
        // 商家自带渠道（外部接入）：凭据是它自己的加密密文，不看平台 env、不走桩
        if (NotifyChannel.SCOPE_MERCHANT.equals(ch.getScope())) {
            boolean hasSecret = ch.getSecretCipher() != null && !ch.getSecretCipher().isBlank();
            return hasSecret ? NotifyChannel.STATUS_READY : NotifyChannel.STATUS_UNCONFIGURED;
        }
        if (channelService.isStub(ch.getChannelType(), ch.getProvider())) {
            return NotifyChannel.STATUS_STUB;
        }
        if (!channelService.credsReady(ch.getChannelType(), ch.getProvider())) {
            return NotifyChannel.STATUS_UNCONFIGURED;
        }
        return NotifyChannel.STATUS_READY;
    }

    /**
     * 软启停。**INAPP 拒绝关**：站内信是事实记录，与场景×通道里 INAPP 不可关同理，
     * 前端被绕过也兜住。
     */
    public NotifyChannel setEnabled(String channelNo, boolean on, String operatorNo) {
        NotifyChannel ch = byNo(channelNo);
        if (ch == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (NotifyChannel.TYPE_INAPP.equals(ch.getChannelType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        ch.setEnabled(on);
        ch.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(ch));
        return ch;
    }
}
