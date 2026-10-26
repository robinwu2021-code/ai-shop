package ai.neargo.shop.pay.channel.api;

/** 来源端：从什么端发起（渠道接入统一封装设计 §11.2）。与 {@link CallerType} 是两个维度 */
public enum ClientType { H5, APP_IOS, APP_ANDROID, MINI_PROGRAM, POS, SERVER, OPS_WEB, BIZ_APP }
