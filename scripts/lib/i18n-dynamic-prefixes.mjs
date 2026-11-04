/** Recognize concatenated translation namespaces, never generic ID strings. */
export function concatenatedTranslationPrefixes(source) {
  return [...source.matchAll(/(?:\b(?:t|tt)|\$t|\bi18n\.global\.t)\s*\(\s*["']([A-Za-z]\w*(?:\.\w+)*\.)["']\s*\+/g)].map(match => match[1]);
}
