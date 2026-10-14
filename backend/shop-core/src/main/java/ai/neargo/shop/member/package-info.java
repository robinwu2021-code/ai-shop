/**
 * 会员域：**某个自然人与某家商家的关系**。
 *
 * <p>与 {@code user} 域的分工：那边管「这个人是谁」（账号、人档、登录），
 * 这边管「他跟这家店什么关系」（什么时候来的、来过几次、打了什么标签、能不能被触达）。
 *
 * <p>与 {@code promotion} 域的分工：营销要问会员「他是不是熟客」（走
 * {@code MemberQueryPort}），会员**不问营销** —— 来源里记的 {@code activityNo}
 * 只是一个字符串，不 import 营销的类。这个方向由 ArchUnit 守着，
 * 一旦反过来，两个域就再也拆不开。
 */
package ai.neargo.shop.member;
