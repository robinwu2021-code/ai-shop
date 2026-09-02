package ai.neargo.shop.pay.channel.master.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.channel.master.ChannelMessageQueryService;
import ai.neargo.shop.pay.mapper.ChannelMappers.ChannelMessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ChannelMessageQueryServiceImpl implements ChannelMessageQueryService {

    /**
     * 固定口径，端上必须显示。
     *
     * <p>放在服务端而不是端上写死：报文将来真的能重放时（接了通道原件留存），
     * 改的是这一行，不是三个端各改一遍 —— 而漏改的那一端会一直说着旧话。
     */
    private static final String NOTE =
            "报文已脱敏（签名、证书序列号、Authorization 都不入库），"
                    + "不能拿去重放验签 —— 要验签请到通道后台调原件。";

    private final ChannelMessageMapper mapper;

    public ChannelMessageQueryServiceImpl(ChannelMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ChannelMessageQueryService.Page page(String payChannel, String msgType, String outcome,
                                                String bizNo, long pageNo, long size) {
        var p = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<StlChannelMessage>(Math.max(pageNo, 1), Math.min(Math.max(size, 1), 100));
        var q = Wrappers.<StlChannelMessage>lambdaQuery()
                .eq(payChannel != null && !payChannel.isBlank(),
                        StlChannelMessage::getPayChannel, payChannel)
                .eq(msgType != null && !msgType.isBlank(), StlChannelMessage::getMsgType, msgType)
                .eq(outcome != null && !outcome.isBlank(), StlChannelMessage::getOutcome, outcome)
                .eq(bizNo != null && !bizNo.isBlank(), StlChannelMessage::getBizNo, bizNo)
                // 倒序：排查从最近一次开始，不是从第一次
                .orderByDesc(StlChannelMessage::getId);
        /*
         * executeWithoutScope：这张表**没有数据域锚点**（通道推过来的报文
         * 在认领到单号之前不属于任何商家），而 ops 的读路径默认带域过滤 ——
         * 不绕开的话列表恒为空，且不报错。
         */
        var res = DataScopeContext.executeWithoutScope(() -> mapper.selectPage(p, q));
        return new ChannelMessageQueryService.Page(
                res.getRecords().stream().map(ChannelMessageQueryServiceImpl::toVO).toList(),
                res.getTotal(), res.getCurrent(), res.getSize(), NOTE);
    }

    @Override
    public Optional<MessageVO> find(String messageNo) {
        if (messageNo == null || messageNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                        mapper.selectOne(Wrappers.<StlChannelMessage>lambdaQuery()
                                .eq(StlChannelMessage::getMessageNo, messageNo).last("LIMIT 1"))))
                .map(ChannelMessageQueryServiceImpl::toVO);
    }

    private static MessageVO toVO(StlChannelMessage m) {
        Long at = m.getCreatedAt() == null ? null
                : m.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new MessageVO(m.getMessageNo(), m.getPayChannel(), m.getMsgType(), m.getApi(),
                m.getBizNo(), m.getPaymentNo(), m.getOutcome(), m.getReason(),
                m.getPayload(), m.getHeaders(), at);
    }
}
