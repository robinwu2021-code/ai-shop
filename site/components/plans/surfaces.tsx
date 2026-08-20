import { SURFACES, type Status } from "@/lib/capabilities";
import { Section, SectionHead } from "@/components/ui/section";

/** 状态角标。没有状态 = 已上线，不加角标 —— 满屏「已上线」等于没有信息 */
export function StatusTag({ status }: { status?: Status }) {
  if (!status) return null;
  return (
    <span className="ml-1.5 rounded-full border border-line-strong px-1.5 py-0.5 align-middle text-[10.5px] font-semibold whitespace-nowrap text-muted">
      {status}
    </span>
  );
}

/**
 * 包含哪些端。
 *
 * 商家问「你们有小程序还是 App」时，真正想确认的是**顾客用什么打开、自己用什么管**。
 * 所以这张表按「谁在用」排，而不是按技术栈排。
 */
export function Surfaces() {
  return (
    <Section id="surfaces" className="bg-panel">
      <SectionHead
        kicker="What you get"
        title="订阅包含的端与形态"
        lede="同一套账号与数据贯穿各端。顾客用小程序或 App 下单，你用商家端处理；两端的商品、订单、库存是同一份，不需要分别维护。"
      />

      <div className="mt-10 grid gap-4.5 sm:grid-cols-2">
        {SURFACES.map((s) => (
          <div key={s.name} className="rounded-card border border-line bg-white px-6 pt-6 pb-6.5">
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <h3 className="text-[19px] font-semibold">{s.name}</h3>
              <span className="text-[13.5px] text-muted">{s.who}</span>
            </div>
            <ul className="mt-4 flex flex-wrap gap-2">
              {s.forms.map((f) => (
                <li
                  key={f.label}
                  className="rounded-full bg-panel px-3 py-1.5 text-[13.5px] font-medium"
                >
                  {f.label}
                  <StatusTag status={f.status} />
                </li>
              ))}
            </ul>
            <p className="mt-4 text-[14.5px] text-muted">{s.desc}</p>
          </div>
        ))}
      </div>
    </Section>
  );
}
