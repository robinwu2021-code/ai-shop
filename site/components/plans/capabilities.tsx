import { DOMAINS } from "@/lib/capabilities";
import { StatusTag } from "@/components/plans/surfaces";
import { Section, SectionHead } from "@/components/ui/section";

/**
 * 功能域清单。
 *
 * 除「门店与员工」外的全部功能域**免费版即包含**，这一点必须写在最前面 ——
 * 免费档常见的做法是砍功能，而这套系统砍的是额度（能开几家店、能加几个人），
 * 不说清楚的话，商家会默认免费版是个残废版本，然后去问竞品要报价。
 */
export function Capabilities() {
  return (
    <Section id="capabilities">
      <SectionHead
        kicker="Capabilities"
        title="功能清单"
        lede="下列功能域除「门店与员工」外，免费版即全部包含。档位之间的差异是额度与跨店数据，不是功能阉割。带角标的条目尚未上线，不计入当前承诺。"
      />

      <div className="mt-10 grid gap-4.5 lg:grid-cols-2">
        {DOMAINS.map((d) => (
          <section
            key={d.name}
            className="rounded-card border border-line px-6 pt-6 pb-6.5"
            aria-labelledby={`cap-${d.name}`}
          >
            <div className="flex flex-wrap items-center gap-2.5">
              <h3 id={`cap-${d.name}`} className="text-[18px] font-semibold">
                {d.name}
              </h3>
              <span
                className={`rounded-full px-2.5 py-1 text-[11.5px] font-semibold ${
                  d.tier === "PRO" ? "bg-tint text-brand-deep" : "bg-panel text-muted"
                }`}
              >
                {d.tier === "PRO" ? "专业版起" : "全部档位"}
              </span>
            </div>
            <p className="mt-2 text-[14px] text-muted">{d.lede}</p>
            <ul className="mt-4 grid gap-2.5">
              {d.items.map((it) => (
                <li
                  key={it.label}
                  className="grid grid-cols-[16px_1fr] gap-2.5 text-[14.5px] text-muted"
                >
                  <span className="mt-[7px] size-1.5 rounded-full bg-brand" aria-hidden />
                  <span>
                    {it.label}
                    <StatusTag status={it.status} />
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </Section>
  );
}
