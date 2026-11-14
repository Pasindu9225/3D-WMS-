import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'
import en from './i18n/en.json'

/** Every error code the backend can send must have a sentence for the user (else they see "Something went wrong"). */
it('has a message for every backend error code', () => {
  const root = join(__dirname, '../../../backend/src/main/java')
  const files: string[] = []
  const walk = (dir: string) => readdirSync(dir).forEach((f) => {
    const p = join(dir, f)
    if (statSync(p).isDirectory()) walk(p)
    else if (p.endsWith('.java')) files.push(p)
  })
  walk(root)
  const codes = new Set<string>()
  for (const f of files) {
    for (const m of readFileSync(f, 'utf8').matchAll(/ApiException\(HttpStatus\.[A-Z_]+,\s*"([A-Z_]+)"/g)) codes.add(m[1]!)
  }
  expect(codes.size).toBeGreaterThan(20)
  const missing = [...codes].filter((c) => !(`error.${c}` in en))
  expect(missing).toEqual([])
})
