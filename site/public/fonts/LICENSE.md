# 字体许可

本目录下的 `hx-sc-400.woff2` / `hx-sc-600.woff2` 是 **Noto Sans SC** 的子集，
由 `site/scripts/subset-fonts.mjs` 从上游可变字体裁出（只保留站点用到的字，两档定重）。

```
Noto Sans SC
Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'.
Copyright 2022 The Noto Project Authors (https://github.com/notofonts/noto-cjk)

以 SIL Open Font License, Version 1.1 授权。
许可全文：https://scripts.sil.org/OFL
上游仓库：https://github.com/notofonts/noto-cjk
```

**为什么这份文件必须在**：OFL 要求字体（含派生的子集）分发时随附版权声明与许可。
子集化时我们用 `--name-IDs=` 剥掉了 name 表（那是体积，不是信息），
于是字体文件自身不再携带这些声明 —— 只能由这份文件来承担。**删掉它就等于无授权分发。**

子集不改字形、不改名，仍属 OFL 允许的派生。
