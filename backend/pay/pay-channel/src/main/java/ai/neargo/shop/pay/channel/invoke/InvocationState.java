package ai.neargo.shop.pay.channel.invoke;

/** 单次调用的生命周期（渠道接入统一封装设计 §9.2） */
public enum InvocationState {
    CREATED, VALIDATED, SENT, RESPONDED, CLASSIFIED, FOLLOWUP_SCHEDULED, RESOLVED
}
