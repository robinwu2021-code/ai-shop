---
title: 下载
slug: /download/
description: 商家端「虹选商家」用于接单、发货、核销与对账；消费者端「虹选 · 好物」用于浏览附近门店、下单与取货。
audience: 商家与消费者
goal: 下载对应的那一端
---

## 下载

```yaml
type: hero
```

商家端用于接单、发货、核销与对账；消费者端用于浏览附近门店、下单与取货。
未上架的入口显示「即将上线」。

---

## 商家端 · 虹选商家

```yaml
type: cta
tone: brand
cta: [Android 安装包 · {{download.merchantAndroidVersion}}, 微信小程序]
ctaHref: ["{{download.merchantAndroid}}", ""]
```

接单、发货、核销、对账，以及多门店经营数据。**iOS 版规划中。**

安卓安装包请使用**手机浏览器**打开下载，微信内置浏览器会拦截该下载。

---

## 消费者端 · 虹选 · 好物

```yaml
type: cta
cta: [App Store, Android 下载, 微信小程序]
ctaHref: ["{{download.consumerAppStore}}", "{{download.consumerAndroid}}", "{{download.consumerMiniProgram}}"]
```

浏览附近门店、下单、就近取货。不安装 App 时可使用微信小程序。

---

## 消费者的购买流程

```yaml
type: timeline
```

1. **浏览** —— 展示附近门店，营业时间、可否配送、送达时长均在列表中标明。
2. **下单** —— 一笔订单可包含多家门店的商品，按门店拆单，各自履约。
3. **取货** —— 到店核销取货码，或由商家配送；服务类可预约上门时间。

---

## 尚未开店？

```yaml
type: cta
cta: [免费开店, 查看支持的行业]
ctaHref: ["{{site.merchantEntry}}", "/scenarios/"]
```

不收取入驻费、年费与押金。
