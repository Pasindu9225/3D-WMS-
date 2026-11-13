// Bundle budget (docs/SPEC.md): admin initial JS under 400 KB gzipped. Run after `npm run build`.
// Initial JS = the entry script plus everything index.html preloads. Lazy page chunks are not counted.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'

const BUDGET_KB = 400
const dist = new URL('../dist/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const html = readFileSync(join(dist, 'index.html'), 'utf8')
const files = [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+\.js)"/g)].map((m) => m[1])
if (files.length === 0) {
  console.error('No initial JS found in dist/index.html. Did the build run?')
  process.exit(1)
}
let total = 0
for (const f of files) {
  const kb = gzipSync(readFileSync(join(dist, f))).length / 1024
  total += kb
  console.log(`${kb.toFixed(1).padStart(8)} KB  ${f}`)
}
console.log(`${total.toFixed(1).padStart(8)} KB  initial JS (gzip), budget ${BUDGET_KB} KB`)
if (total > BUDGET_KB) {
  console.error('Over budget.')
  process.exit(1)
}
