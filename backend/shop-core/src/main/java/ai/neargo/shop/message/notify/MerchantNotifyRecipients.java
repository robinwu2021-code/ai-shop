package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.entity.MchNotifyRecipient;
import ai.neargo.shop.message.mapper.MessageMappers.MchNotifyRecipientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 门店的通知收件地址：读、写、校验（TDD-来单四渠道与商家通知设置 §2.5）。
 *
 * <p>三条通道的地址在一张表的三列上（{@link MchNotifyRecipient}）。
 * 这个类是**唯一的读写入口** —— 逗号怎么切、上限几个、webhook 怎么加解密，
 * 都收在这里；各处自己解析的话，「最多两个」这条规矩迟早有一处没守住。
 */
@Service
public class MerchantNotifyRecipients {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotifyRecipients.class);

    /** 大陆手机号。**不放宽到国际号**：短信通道走的是阿里云国内模板，别的号发不出去 */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    /** 够用的邮箱形状判断。严格校验靠发信本身 —— 这里拦的是「明显填错」 */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private final MchNotifyRecipientMapper mapper;

    public MerchantNotifyRecipients(MchNotifyRecipientMapper mapper) {
        this.mapper = mapper;
    }

    /** 这家店那一行；没配过返回 null */
    public MchNotifyRecipient rowOf(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<MchNotifyRecipient>lambdaQuery()
                        .eq(MchNotifyRecipient::getStoreNo, storeNo).last("limit 1")));
    }

    /**
     * 额外的短信号（不含店主那一个）。
     *
     * <p>**切分在这里做一次**：逗号分隔是存储形态，不是接口形态 ——
     * 让调用方各自 split 的话，有人会忘了 trim、有人会把空串当成一个号。
     */
    public List<String> extraPhones(String storeNo) {
        MchNotifyRecipient r = rowOf(storeNo);
        return splitPhones(r == null ? null : r.getSmsPhones());
    }

    /** 邮件地址；没填返回空 */
    public Optional<String> email(String storeNo) {
        MchNotifyRecipient r = rowOf(storeNo);
        return Optional.ofNullable(r == null ? null : r.getEmail()).filter(e -> !e.isBlank());
    }

    /**
     * 企微群地址。
     *
     * <p><b>返回值是凭据</b>（库里存的是明文，2026-10-10 的决定）——
     * 调用方只许交给 {@link WeComBotSender}，**不进日志、不进响应体**。
     * 存明文是一回事，下发给前端是另一回事：回显只给「配过没有」。
     */
    public Optional<String> wecomWebhook(String storeNo) {
        MchNotifyRecipient r = rowOf(storeNo);
        return Optional.ofNullable(r == null ? null : r.getWecomWebhook())
                .map(String::trim).filter(w -> !w.isEmpty());
    }

    // ---------------------------------------------------------------- 写

    /**
     * 改额外短信号。
     *
     * @param phones 完整的新名单（不是追加）—— 删一个就是传剩下的那些。
     *               超过 {@link MchNotifyRecipient#MAX_EXTRA_PHONES} 个、
     *               或有一个不是大陆手机号，整笔拒绝
     */
    @Transactional
    public void setPhones(String storeNo, List<String> phones, String operator) {
        List<String> clean = phones == null ? List.of()
                : phones.stream().map(String::trim).filter(p -> !p.isEmpty()).distinct().toList();
        if (clean.size() > MchNotifyRecipient.MAX_EXTRA_PHONES
                || !clean.stream().allMatch(p -> PHONE.matcher(p).matches())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        upsert(storeNo, operator, r -> r.setSmsPhones(clean.isEmpty() ? null : String.join(",", clean)));
    }

    /** @param email 空串/null = 不发邮件 */
    @Transactional
    public void setEmail(String storeNo, String email, String operator) {
        String clean = email == null ? "" : email.trim();
        if (!clean.isEmpty() && !EMAIL.matcher(clean).matches()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        upsert(storeNo, operator, r -> r.setEmail(clean.isEmpty() ? null : clean));
    }

    /**
     * 存企微群地址。
     *
     * <p>**前缀必须对**：填错的后果是「开关开着却永远收不到」，
     * 而那时没有任何线索指向这一格 —— 企微那边连请求都收不到，日志里什么都没有。
     */
    @Transactional
    public void setWecom(String storeNo, String webhook, String operator) {
        String clean = webhook == null ? "" : webhook.trim();
        if (!clean.isEmpty() && !clean.startsWith("https://qyapi.weixin.qq.com/")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        upsert(storeNo, operator, r -> r.setWecomWebhook(clean.isEmpty() ? null : clean));
    }

    private void upsert(String storeNo, String operator,
                        java.util.function.Consumer<MchNotifyRecipient> change) {
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        DataScopeContext.executeWithoutScope(() -> {
            MchNotifyRecipient row = mapper.selectOne(Wrappers.<MchNotifyRecipient>lambdaQuery()
                    .eq(MchNotifyRecipient::getStoreNo, storeNo).last("limit 1"));
            boolean isNew = row == null;
            if (isNew) {
                row = new MchNotifyRecipient();
                row.setStoreNo(storeNo);
            }
            change.accept(row);
            row.setUpdatedBy(operator);
            /*
             * ⚠️ 清空一个字段要走 updateById 之外的路：MyBatis-Plus 的 updateById
             * **跳过 null 字段**，于是「把邮箱删掉」那句 set 根本不生成，
             * 界面上显示删掉了、下次读回来它还在。这里用 UpdateWrapper 显式 set 三列。
             */
            if (isNew) {
                mapper.insert(row);
            } else {
                mapper.update(null, Wrappers.<MchNotifyRecipient>lambdaUpdate()
                        .eq(MchNotifyRecipient::getId, row.getId())
                        .set(MchNotifyRecipient::getSmsPhones, row.getSmsPhones())
                        .set(MchNotifyRecipient::getEmail, row.getEmail())
                        .set(MchNotifyRecipient::getWecomWebhook, row.getWecomWebhook())
                        .set(MchNotifyRecipient::getUpdatedBy, operator));
            }
            return null;
        });
    }

    /** 逗号切分 + 去空白去空串。存储形态只在这里展开 */
    public static List<String> splitPhones(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim).filter(p -> !p.isEmpty()).toList();
    }
}
