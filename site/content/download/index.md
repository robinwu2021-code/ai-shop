---
title: 下载
slug: /download/
description: 顾客端与商家端的下载入口。顾客用微信小程序或 App 下单；商家用商家端接单、发货、核销与对账，安卓可直接下载安装包。
audience: 顾客与商家（两端分开，别下错）
goal: 下载对应的那一端
---

## 两个 App，别下错。

```yaml
type: hero
```

**顾客用「虹选 · 好物」买东西，商家用「虹选商家」接单。**
两个是不同的应用，装的时候看清楚名字。未上架的入口会显示「即将上线」。

---

## 顾客端 · 虹选 · 好物

```yaml
type: cta
cta: [App Store, Android 下载, 微信小程序]
ctaHref: ["{{download.consumerAppStore}}", "{{download.consumerAndroid}}", "{{download.consumerMiniProgram}}"]
```

打开就是附近的店：挑好、下单、就近取货。不装 App，用微信小程序也一样。

---

## 商家端 · 虹选商家

```yaml
type: cta
tone: brand
cta: [Android 安装包, 微信小程序]
ctaHref: ["{{download.merchantAndroid}}", ""]
```

接单、发货、核销、对账，还有几家店的经营数据。**iOS 版规划中。**

安卓安装包请用**手机浏览器**打开下载 —— 在微信里点会被拦住，那不是链接坏了。

---

## 顾客那侧是这样买的

```yaml
type: timeline
```

1. **挑** —— 打开就是附近的店，营业时间、能不能送、多久到都写在列表里。
2. **下单** —— 一个订单里可以有几家店的东西，按店拆单，各自履约。
3. **就近取货** —— 到店核销一个码，或等老板送上门；服务类还能预约上门时间。

---

## 还没开店？

```yaml
type: cta
cta: [免费开店, 看看支持哪些店]
ctaHref: ["{{site.merchantEntry}}", "/scenarios/"]
```

零门槛开店，0 元起步。不用押金、不用年费。
