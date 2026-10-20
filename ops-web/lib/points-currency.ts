/** UI units are expressed as exact minor-unit amounts in API commands. */
export const pointsCurrencies = [
  { code: "CNY", name: "人民币", english: "Chinese yuan", scale: 2, units: [
    { code: "fen", name: "分", english: "Fen", minor: 1 }, { code: "jiao", name: "角", english: "Jiao", minor: 10 }, { code: "yuan", name: "元", english: "Yuan", minor: 100 },
  ] },
  { code: "USD", name: "美元", english: "US dollar", scale: 2, units: [
    { code: "cent", name: "美分", english: "Cent", minor: 1 }, { code: "dollar", name: "美元", english: "Dollar", minor: 100 },
  ] },
  { code: "KWD", name: "科威特第纳尔", english: "Kuwaiti dinar", scale: 3, units: [
    { code: "fils", name: "费尔（Fils）", english: "Fils", minor: 1 }, { code: "dinar", name: "第纳尔", english: "Dinar", minor: 1000 },
  ] },
] as const;
export function pointsCurrency(code: string) { return pointsCurrencies.find((c) => c.code === code); }
