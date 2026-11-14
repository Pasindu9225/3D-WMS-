/** Bin search for the 3D view: exact code first (any case), otherwise the first code that starts with it. */
export function findBin(codes: string[], query: string): number | null {
  const q = query.trim().toUpperCase()
  if (!q) return null
  const exact = codes.indexOf(q)
  if (exact >= 0) return exact
  const prefix = codes.findIndex((c) => c.startsWith(q))
  return prefix >= 0 ? prefix : null
}

/** Up to `max` codes starting with the query, sorted, for the search box suggestions. */
export function suggestions(codes: string[], query: string, max = 20): string[] {
  const q = query.trim().toUpperCase()
  if (q.length < 2) return []
  const out: string[] = []
  for (const c of codes) {
    if (c.startsWith(q)) out.push(c)
  }
  return out.sort().slice(0, max)
}
