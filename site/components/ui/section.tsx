/** 版心 + 纵向节奏。抽出来是因为七屏各写一遍 clamp 迟早会漂。 */
export function Section({
  id,
  className = "",
  children,
}: {
  id?: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} className={`py-[clamp(56px,7vw,100px)] ${className}`}>
      <div className="edge mx-auto max-w-[1160px]">{children}</div>
    </section>
  );
}

export function SectionHead({
  kicker,
  title,
  lede,
  invert = false,
}: {
  kicker: string;
  title: React.ReactNode;
  lede?: React.ReactNode;
  /** 压在深色底上时反白 */
  invert?: boolean;
}) {
  return (
    <div>
      <span
        className={`mb-3.5 block text-[12.5px] tracking-[0.14em] uppercase ${invert ? "text-white/50" : "text-muted"}`}
      >
        {kicker}
      </span>
      <h2
        className={`font-display text-[clamp(27px,3.6vw,42px)] leading-tight font-normal tracking-[-0.01em] ${invert ? "text-white" : ""}`}
      >
        {title}
      </h2>
      {lede && (
        <p className={`mt-4 max-w-[56ch] text-[17px] ${invert ? "text-white/66" : "text-muted"}`}>
          {lede}
        </p>
      )}
    </div>
  );
}
