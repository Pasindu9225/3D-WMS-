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
const heavy = []
for (const f of files) {
  const code = readFileSync(join(dist, f))
  // SPEC v0.3: the 3D engine loads only on 3D pages; Konva only in the layout editor.
  if (code.includes('WebGLRenderer')) heavy.push(`${f} contains three.js`)
  if (code.includes('konvajs-content')) heavy.push(`${f} contains Konva`)
  const kb = gzipSync(code).length / 1024
  total += kb
  console.log(`${kb.toFixed(1).padStart(8)} KB  ${f}`)
}
console.log(`${total.toFixed(1).padStart(8)} KB  initial JS (gzip), budget ${BUDGET_KB} KB`)
if (heavy.length > 0) {
  console.error(`Heavy libraries in the initial bundle:\n  ${heavy.join('\n  ')}`)
  process.exit(1)
}
if (total > BUDGET_KB) {
  console.error('Over budget.')
  process.exit(1)
}

// Worker phone app (SPEC: under 250 KB gzipped on 2 GB phones): the entry plus the worker layout and home
// chunks and everything they import statically. Lazy imports (the zxing fallback, 3D) are not counted.
const WORKER_BUDGET_KB = 250
const manifest = JSON.parse(readFileSync(join(dist, '.vite', 'manifest.json'), 'utf8'))
const seen = new Set()
const visit = (key) => {
  const entry = manifest[key]
  if (!entry || seen.has(key)) return
  seen.add(key)
  for (const dep of entry.imports ?? []) visit(dep)
}
for (const [key, entry] of Object.entries(manifest)) {
  if (entry.isEntry || /features\/worker\/Worker(Layout|Home)\.tsx$/.test(key)) visit(key)
}
let worker = 0
for (const key of seen) worker += gzipSync(readFileSync(join(dist, manifest[key].file))).length / 1024
console.log(`${worker.toFixed(1).padStart(8)} KB  worker app first load (gzip), budget ${WORKER_BUDGET_KB} KB`)
if (worker > WORKER_BUDGET_KB) {
  console.error('Worker app over budget.')
  process.exit(1)
}
