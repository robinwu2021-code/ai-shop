package ai.neargo.shop.merchant.brand.impl;

import ai.neargo.shop.merchant.brand.ManagementRepository;

/**
 * 门店入连锁的准入：唯一的判断入口（TDD-平台基础库换表「连锁按权益准入」，2026-11-28 用户定：不走审批，按权益收费）。
 *
 * <p>门店须持有有效的连锁权益 {@value #ENTITLEMENT}——来源不限（付费、赠送、运营授予），都看平台库里由商城交付写入的
 * 权益时间片 {@code mch_store_end_grant}。旧模型门店（{@code commerce_model} 不是 {@code STORE_V1}）不受新商业模型约束，
 * 照旧放行，与功能矩阵「旧模型门店跳过商业状态」同口径。退出连锁不判。
 *
 * <p>将来连锁要给免费额度、按集团包几家等，在这里加准入依据，调用方不动。
 */
public final class ChainAdmissionPolicy {

    public static final String ENTITLEMENT = "END_CHAIN";
    public static final String BASIS_ENTITLEMENT = "ENTITLEMENT";
    public static final String BASIS_LEGACY = "LEGACY";
    /** 预检返回给前端的阻断项：门店没有连锁权益，下一步去商城购买 */
    public static final String BLOCKER = "CHAIN_ENTITLEMENT_REQUIRED";

    /**
     * @param basis         准入依据；null = 不准入
     * @param entitledUntil 当前权益段的结束（epoch 毫秒）；null = 长期或不适用
     */
    public record Admission(String basis, Long entitledUntil) {
        public boolean allowed() {
            return basis != null;
        }
    }

    private final ManagementRepository db;

    public ChainAdmissionPolicy(ManagementRepository db) {
        this.db = db;
    }

    public Admission admit(String tenantNo, String storeNo) {
        var store = db.one("SELECT commerce_model FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",
                tenantNo, storeNo);
        if (store == null) {
            return new Admission(null, null);
        }
        if (!"STORE_V1".equals(store.get("commerce_model"))) {
            return new Admission(BASIS_LEGACY, null);
        }
        long now = System.currentTimeMillis();
        var segments = db.rows("SELECT end_at FROM mch_store_end_grant WHERE tenant_no=? AND store_no=?"
                + " AND entitlement_code=? AND state IN ('SCHEDULED','ACTIVE','ENDED') AND deleted=0"
                + " AND start_at<=? AND (end_at IS NULL OR end_at>?)", tenantNo, storeNo, ENTITLEMENT, now, now);
        if (segments.isEmpty()) {
            return new Admission(null, null);
        }
        Long until = null;
        for (var segment : segments) {
            Object end = segment.get("end_at");
            if (end == null) {
                return new Admission(BASIS_ENTITLEMENT, null);
            }
            long value = ((Number) end).longValue();
            until = until == null ? value : Math.max(until, value);
        }
        return new Admission(BASIS_ENTITLEMENT, until);
    }
}
