import postcss from 'postcss';
/** Comments and declaration values cannot define a usable class. */
export function declaredCssClasses(css) {
  const classes = new Set();
  postcss.parse(css).walkRules(rule => {
    for (const match of rule.selector.matchAll(/\.([\w-]+)/g)) classes.add(match[1]);
  });
  return classes;
}
