package ai.neargo.shop.pay.terminal.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_terminal（db/pay V16 · pay-guard 开发计划 M6.1）：刷卡机终端（自研 App）：绑定收款商户与门店。
 *
 * <p>字段注释取自表结构真源 pay-service-database.json。
 */
@Getter
@Setter
@TableName("pay_terminal")
public class PayTerminal extends PayBaseEntity {

    /** 终端序列号（设备上可见，对外） */
    private String terminalSn;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 绑定的收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 机型 */
    private String model;

    /** 读卡套件型号 / 版本（按渠道认证清单校验） */
    private String readerKit;

    /** 终端 App 版本 */
    private String appVersion;

    /** REGISTERED / ACTIVE / LOST / RETIRED */
    private String status;

    /** 一次性激活码哈希 */
    private String activationCodeSha256;

    /** 激活码过期（10 分钟） */
    private LocalDateTime activationExpireAt;

    /** 激活时设备指纹 */
    private String deviceFingerprint;

    /** 设备密钥 sec_key.id（owner_type=TERMINAL），激活时生成 */
    private Long secKeyId;

    /** 单终端日退款上限 */
    private Long dailyRefundLimitMinor;

    /** 最近心跳 */
    private LocalDateTime lastSeenAt;

    /** 激活时间 */
    private LocalDateTime activatedAt;
}
