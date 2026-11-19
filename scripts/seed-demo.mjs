#!/usr/bin/env node
/**
 * Fills a company with sample data for testing, through the app's own API (as a person would): a 60 m × 40 m
 * warehouse "DEMO1" with doors, docks and five zones; pallet racking, shelving and floor storage; published; then
 * suppliers, customers, 20 products with real carton sizes and handling rules, purchase orders received and put away (so the 3D view
 * shows stock), and three sales orders (two released for picking).
 *
 * Local testing only. Sign-in comes from the environment, never from this file:
 *   WMS_EMAIL=... WMS_PASSWORD=... node scripts/seed-demo.mjs        (WMS_URL defaults to http://localhost:5180)
 * Safe to run twice: things that already exist (by code) are reused, and the warehouse is not built again.
 */
import { randomUUID } from 'node:crypto'

const BASE = process.env.WMS_URL ?? 'http://localhost:5180'
const EMAIL = process.env.WMS_EMAIL
const PASSWORD = process.env.WMS_PASSWORD
if (!EMAIL || !PASSWORD) {
  console.error('Set WMS_EMAIL and WMS_PASSWORD (a company admin) first.')
  process.exit(1)
}

// ---------------------------------------------------------------- tiny API client (cookies + CSRF like the browser)
const jar = new Map()
async function api(method, path, body, headers = {}) {
  const h = { ...headers, cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') }
  if (body !== undefined) h['content-type'] = 'application/json'
  if (method !== 'GET') h['x-xsrf-token'] = jar.get('XSRF-TOKEN') ?? ''
  const res = await fetch(BASE + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) })
  for (const c of res.headers.getSetCookie()) {
    const [pair] = c.split(';')
    const i = pair.indexOf('=')
    jar.set(pair.slice(0, i), pair.slice(i + 1))
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  return { status: res.status, data }
}
async function must(method, path, body, headers) {
  let r = await api(method, path, body, headers)
  for (let wait = 1; r.status === 429 && wait <= 6; wait++) { // the app limits bursts: wait and try again
    await new Promise((ok) => setTimeout(ok, wait * 5000))
    r = await api(method, path, body, headers)
  }
  if (r.status >= 300) throw new Error(`${method} ${path} → ${r.status} ${JSON.stringify(r.data)}`)
  return r.data
}
const key = () => randomUUID()

// ---------------------------------------------------------------- the sample data
const RACK_TYPES = [
  { code: 'DEMO-PAL', name: 'Pallet rack 2.7 m (demo)', widthMm: 2700, depthMm: 1100, heightMm: 6000, levels: 4,
    binsPerLevel: 3, maxWeightPerLevelG: 3_000_000, style: 'PALLET', uprightColor: '#1d4ed8', beamColor: '#f97316' },
  { code: 'DEMO-SHELF', name: 'Shelving 2 m (demo)', widthMm: 2000, depthMm: 600, heightMm: 2400, levels: 5,
    binsPerLevel: 4, maxWeightPerLevelG: 300_000, style: 'SHELF', uprightColor: '#475569', beamColor: '#94a3b8' },
  { code: 'DEMO-FLOOR', name: 'Floor row 4 pallets (demo)', widthMm: 5000, depthMm: 1200, heightMm: 3600, levels: 3,
    binsPerLevel: 4, maxWeightPerLevelG: 4_000_000, style: 'FLOOR', uprightColor: '#1d4ed8', beamColor: '#f97316' },
]

const SUPPLIERS = [
  { code: 'SUP-CEY', name: 'Ceylon Foods Ltd', contactName: 'Nimal Perera', phone: '011 234 5678' },
  { code: 'SUP-LAN', name: 'Lanka Home Care', contactName: 'Kumari Silva', phone: '011 345 6789' },
  { code: 'SUP-ISL', name: 'Island Electricals', contactName: 'Ruwan Fernando', phone: '011 456 7890' },
]
// 3PL clients (v2.1): the companies whose goods DEMO1 stores, each limited to its HS sections (world customs groups)
const CLIENTS = [
  { code: 'CLI-CEY', name: 'Ceylon Foods Ltd', contactName: 'Nimal Perera', phone: '011 234 5678', allowedHsSections: [1, 2, 3, 4] },
  { code: 'CLI-LAN', name: 'Lanka Home Care', contactName: 'Kumari Silva', phone: '011 345 6789', allowedHsSections: [6, 10] },
  { code: 'CLI-ISL', name: 'Island Electricals', contactName: 'Ruwan Fernando', phone: '011 456 7890', allowedHsSections: [16] },
]
// whose goods: each supplier's products belong to the matching client
const OWNER_OF = { 'SUP-CEY': 'CLI-CEY', 'SUP-LAN': 'CLI-LAN', 'SUP-ISL': 'CLI-ISL' }
// world customs (HS) codes of the products that have no other handling
const HS = { 'DHAL-1KG': '071340', 'TEA-400G': '090230', 'MILK-1L': '040120', 'COCO-OIL': '151311', 'BISC-200': '190590',
  'SOAP-100': '340111', 'DETG-1KG': '340220', 'DISH-500': '340220', 'TISS-10': '481810', 'BULB-9W': '853950', 'FAN-16': '841451',
  'KETL-17': '851671', 'EXT-4W': '854442', 'RCOOK-1': '851660' }

const CUSTOMERS = [
  { code: 'CUS-KAN', name: 'Kandy City Mart', address: '12 Peradeniya Rd, Kandy' },
  { code: 'CUS-GAL', name: 'Galle Fort Stores', address: '5 Church St, Galle' },
  { code: 'CUS-NEG', name: 'Negombo Super', address: '88 Main St, Negombo' },
  { code: 'CUS-JAF', name: 'Jaffna Traders', address: '3 Hospital Rd, Jaffna' },
]

// sku, name, category, supplier, weight g, carton L×W×H cm, units per carton, cost (LKR), cartons to buy, expiry?,
// handling (v2.1: HS customs code, storage temperature, UN hazard class, stack limit, fragile)
const PRODUCTS = [
  ['RICE-5KG', 'Samba rice 5 kg', 'Food', 'SUP-CEY', 5100, [50, 35, 30], 4, 1450, 40, false, { hsCode: '100630', stackMax: 5 }],
  ['FISH-1KG', 'Frozen seer fish 1 kg', 'Frozen', 'SUP-CEY', 1050, [40, 30, 20], 10, 2400, 16, true, { hsCode: '030389', storageTemp: 'FROZEN', stackMax: 6 }],
  ['ICEC-1L', 'Vanilla ice cream 1 L', 'Frozen', 'SUP-CEY', 600, [36, 24, 20], 8, 850, 16, true, { hsCode: '210500', storageTemp: 'FROZEN' }],
  ['EGGS-30', 'Eggs, tray of 30', 'Food', 'SUP-CEY', 1900, [60, 30, 25], 4, 1350, 12, true, { hsCode: '040721', stackMax: 1, fragile: true }],
  ['BLCH-1L', 'Bleach 1 L', 'Chemicals', 'SUP-LAN', 1100, [30, 22, 26], 12, 380, 20, false, { hsCode: '282890', hazardClass: '8', stackMax: 3 }],
  ['THIN-1L', 'Paint thinner 1 L', 'Chemicals', 'SUP-LAN', 900, [30, 22, 26], 12, 720, 12, false, { hsCode: '381400', hazardClass: '3', stackMax: 3 }],
  ['DHAL-1KG', 'Red dhal 1 kg', 'Food', 'SUP-CEY', 1020, [40, 30, 20], 12, 420, 30, false],
  ['TEA-400G', 'Ceylon black tea 400 g', 'Food', 'SUP-CEY', 420, [36, 24, 22], 24, 980, 25, true],
  ['MILK-1L', 'UHT milk 1 L', 'Food', 'SUP-CEY', 1050, [30, 20, 25], 12, 390, 50, true],
  ['COCO-OIL', 'Coconut oil 1 L', 'Food', 'SUP-CEY', 950, [32, 24, 28], 12, 1150, 20, false],
  ['BISC-200', 'Cream crackers 200 g', 'Food', 'SUP-CEY', 210, [40, 28, 24], 30, 260, 30, true],
  ['SOAP-100', 'Bath soap 100 g', 'Home care', 'SUP-LAN', 110, [30, 20, 15], 48, 120, 20, false],
  ['DETG-1KG', 'Washing powder 1 kg', 'Home care', 'SUP-LAN', 1030, [45, 30, 25], 10, 640, 30, false],
  ['DISH-500', 'Dishwash liquid 500 ml', 'Home care', 'SUP-LAN', 540, [34, 23, 21], 20, 330, 25, false],
  ['TISS-10', 'Toilet tissue 10 pack', 'Home care', 'SUP-LAN', 900, [60, 40, 35], 6, 950, 40, false],
  ['BULB-9W', 'LED bulb 9 W', 'Electrical', 'SUP-ISL', 60, [25, 20, 15], 50, 290, 10, false],
  ['FAN-16', 'Table fan 16 inch', 'Electrical', 'SUP-ISL', 3900, [55, 45, 30], 1, 7800, 25, false],
  ['KETL-17', 'Electric kettle 1.7 L', 'Electrical', 'SUP-ISL', 1400, [28, 24, 26], 4, 4200, 20, false],
  ['EXT-4W', 'Extension cord 4 way', 'Electrical', 'SUP-ISL', 650, [35, 25, 20], 10, 1650, 15, false],
  ['RCOOK-1', 'Rice cooker 1.8 L', 'Electrical', 'SUP-ISL', 2600, [40, 35, 33], 2, 9500, 20, false],
]

// ---------------------------------------------------------------- layout (all in mm; 60 m × 40 m floor)
function layout(types) {
  const rect = (x0, y0, x1, y1) => [{ x: x0, y: y0 }, { x: x1, y: y0 }, { x: x1, y: y1 }, { x: x0, y: y1 }]
  const zones = [
    { code: 'A', type: 'STORAGE', name: 'Pallet storage', polygon: rect(2000, 2000, 40000, 26000) },
    { code: 'P', type: 'PICKING', name: 'Shelf picking', polygon: rect(42000, 2000, 58000, 26000) },
    { code: 'R', type: 'RECEIVING', name: 'Goods in', polygon: rect(0, 30000, 12000, 40000) },
    { code: 'F', type: 'STORAGE', name: 'Floor stacking', polygon: rect(14000, 30000, 46000, 40000) },
    { code: 'D', type: 'DISPATCH', name: 'Goods out', polygon: rect(48000, 30000, 60000, 40000) },
    // v2.1 handling rules: a freezer and a dangerous goods store in the east wing
    { code: 'Z', type: 'FROZEN', name: 'Freezer', polygon: rect(61000, 2000, 71000, 18000) },
    { code: 'H', type: 'STORAGE', name: 'Chemicals', polygon: rect(61000, 22000, 71000, 38000), hazardClasses: ['3', '8'] },
  ]
  const racks = []
  const add = (prefix, zone, templateId, x, y) =>
    racks.push({ code: `${prefix}${String(racks.filter((r) => r.zone === zone).length + 1).padStart(2, '0')}`,
      templateId, zone, x, y, rotation: 0 })
  // pallet racking: 5 rows of 13, 3.5 m aisles
  for (let row = 0; row < 5; row++) for (let i = 0; i < 13; i++) add('A', 'A', types['DEMO-PAL'], 3350 + i * 2700, 3500 + row * 4600)
  // shelving: 8 rows of 7, 2.4 m aisles
  for (let row = 0; row < 8; row++) for (let i = 0; i < 7; i++) add('P', 'P', types['DEMO-SHELF'], 44000 + i * 2000, 3500 + row * 3000)
  // floor stacking: 2 rows of 6
  for (let row = 0; row < 2; row++) for (let i = 0; i < 6; i++) add('F', 'F', types['DEMO-FLOOR'], 17000 + i * 5200, 31500 + row * 4000)
  // freezer and chemicals store: 3 rows of 3 pallet racks each
  for (let row = 0; row < 3; row++) for (let i = 0; i < 3; i++) add('Z', 'Z', types['DEMO-PAL'], 62850 + i * 2700, 4000 + row * 5000)
  for (let row = 0; row < 3; row++) for (let i = 0; i < 3; i++) add('H', 'H', types['DEMO-PAL'], 62850 + i * 2700, 24000 + row * 5000)
  const openings = [
    { kind: 'DOCK', label: 'Dock 1 (in)', x: 3500, y: 40000, widthMm: 3000, heightMm: 4500 },
    { kind: 'DOCK', label: 'Dock 2 (in)', x: 8500, y: 40000, widthMm: 3000, heightMm: 4500 },
    { kind: 'DOCK', label: 'Dock 3 (out)', x: 51500, y: 40000, widthMm: 3000, heightMm: 4500 },
    { kind: 'DOCK', label: 'Dock 4 (out)', x: 56500, y: 40000, widthMm: 3000, heightMm: 4500 },
    { kind: 'DOOR', label: 'Staff door', x: 0, y: 28000, widthMm: 1200, heightMm: 2100 },
    { kind: 'DOOR', label: 'Office door', x: 30000, y: 0, widthMm: 1500, heightMm: 2400 },
  ]
  return { floor: rect(0, 0, 72000, 40000), openings, zones, racks, settings: { minAisleMm: 1200 } }
}

// ---------------------------------------------------------------- run
async function upsertPartner(kind, p) {
  const r = await api('POST', `/api/v1/${kind}`, p)
  if (r.status === 201 || r.status === 200) return
  if (r.status === 409) return // already there
  throw new Error(`${kind} ${p.code}: ${r.status} ${JSON.stringify(r.data)}`)
}

async function main() {
  await must('GET', '/api/v1/auth/csrf')
  await must('POST', '/api/v1/auth/login', { email: EMAIL, password: PASSWORD })
  const me = await must('GET', '/api/v1/auth/me')
  console.log(`Signed in as ${me.email ?? EMAIL}`)

  // rack types (reuse by code)
  const existingTypes = (await must('GET', '/api/v1/rack-templates?size=100')).items
  const types = {}
  for (const t of RACK_TYPES) {
    const found = existingTypes.find((e) => e.code === t.code)
    types[t.code] = found ? found.id : (await must('POST', '/api/v1/rack-templates', t)).id
  }
  console.log('Rack types: pallet racking, shelving, floor storage')

  // warehouse (built once)
  const warehouses = (await must('GET', '/api/v1/warehouses?size=100')).items
  let wh = warehouses.find((w) => w.code === 'DEMO1')
  const built = wh && wh.layoutStatus === 'PUBLISHED' ? await must('GET', `/api/v1/warehouses/${wh.id}/layout`) : null
  if (built && built.layout.zones.some((z) => z.code === 'Z')) {
    console.log('Warehouse DEMO1 is already built; skipping layout')
  } else if (built) { // built before the freezer and chemicals store existed: add the east wing, racks keep their places
    const fresh = layout(types)
    const wing = (z) => z === 'Z' || z === 'H'
    const doc = { ...built.layout, floor: fresh.floor, // keep what is published (it may have been edited); add the east wing
      zones: [...built.layout.zones, ...fresh.zones.filter((z) => wing(z.code))],
      racks: [...built.layout.racks.map((r) => (r.templateId === types['DEMO-FLOOR'] && !r.widthMm ? { ...r, widthMm: 5000 } : r)), // as published, even if the type was widened since
        ...fresh.racks.filter((r) => wing(r.zone))] }
    const saved = await must('PUT', `/api/v1/warehouses/${wh.id}/layout`, { baseRevision: built.revision, layout: doc })
    const pub = await must('POST', `/api/v1/warehouses/${wh.id}/layout/publish`, { baseRevision: saved.revision })
    console.log(`Warehouse DEMO1 extended with a freezer and a chemicals store: ${pub.racks} racks, ${pub.bins} places`)
  } else {
    wh ??= await must('POST', '/api/v1/warehouses', { code: 'DEMO1', name: 'Demo DC Colombo', widthM: 60, lengthM: 40, wallHeightM: 9 })
    const current = await must('GET', `/api/v1/warehouses/${wh.id}/layout`)
    const saved = await must('PUT', `/api/v1/warehouses/${wh.id}/layout`, { baseRevision: current.status === 'DRAFT' ? current.revision : 0, layout: layout(types) })
    const pub = await must('POST', `/api/v1/warehouses/${wh.id}/layout/publish`, { baseRevision: saved.revision })
    console.log(`Warehouse DEMO1 published: ${pub.racks} racks, ${pub.bins} places`)
  }

  for (const s of SUPPLIERS) await upsertPartner('suppliers', s)
  for (const c of CUSTOMERS) await upsertPartner('customers', c)
  for (const c of CLIENTS) await upsertPartner('clients', c)
  console.log(`Suppliers: ${SUPPLIERS.length}, customers: ${CUSTOMERS.length}, clients (3PL): ${CLIENTS.length}`)

  // products
  const ids = {}
  const body = ([sku, name, category, supplier, weightG, [l, w, h], perCarton, cost, , expiry, handling]) => ({
    sku, name, baseUom: 'EA', weightG, volumeCm3: Math.round((l * w * h) / perCarton), trackBatch: expiry, trackExpiry: expiry,
    costMinor: cost * 100, reorderPoint: perCarton * 3, category, cartonLengthMm: l * 10, cartonWidthMm: w * 10,
    cartonHeightMm: h * 10, unitsPerCarton: perCarton,
    units: perCarton > 1 ? [{ uom: 'CASE', factor: perCarton }] : [],
    handling: { ...(handling ?? {}), hsCode: handling?.hsCode ?? HS[sku] },
    ownerCode: OWNER_OF[supplier],
  })
  for (const spec of PRODUCTS) {
    const r = await api('POST', '/api/v1/products', body(spec))
    if (r.status >= 300 && r.status !== 409) throw new Error(`product ${spec[0]}: ${r.status} ${JSON.stringify(r.data)}`)
  }
  for (const p of (await must('GET', '/api/v1/products?size=100')).items) {
    ids[p.sku] = p.id
    const spec = PRODUCTS.find((s) => s[0] === p.sku)
    if (spec && (!p.handling?.hsCode || p.ownerCode !== OWNER_OF[spec[3]])) await must('PUT', `/api/v1/products/${p.id}`, { ...body(spec), version: p.version }) // made before handling or clients existed
  }
  console.log(`Products: ${PRODUCTS.length} (with carton sizes and handling: frozen, dangerous goods, fragile, stack limits)`)

  // only stock up once: if anything is already in DEMO1, stop here
  const stock = await must('GET', `/api/v1/stock?warehouse=${wh.id}&size=1`).catch(() => ({ total: 0 }))
  // putaway rules, so goods spread as in a real warehouse: food on pallet racks, electricals on shelves,
  // home care stacked on the floor
  const rules = await must('GET', `/api/v1/putaway-rules?warehouse=${wh.id}`)
  if (rules.length === 0) {
    for (const [category, zoneCode, priority] of [['Food', 'A', 10], ['Electrical', 'P', 20], ['Home care', 'F', 30]]) {
      await must('POST', '/api/v1/putaway-rules', { warehouseId: wh.id, priority, category, zoneCode, onlyZone: true })
    }
    console.log('Putaway rules: Food → A, Electrical → P, Home care → F')
  }

  // SEED_MORE=1 receives another delivery even when there is stock already
  if ((stock.total ?? stock.items?.length ?? 0) > 0 && process.env.SEED_MORE !== '1') console.log('DEMO1 already has stock; skipping goods in (SEED_MORE=1 adds another delivery)')
  else await goodsIn(wh)
  await putAwayAll(wh)

  await salesOrders(wh)
  console.log('Done. Open Warehouses → DEMO1 → 3D view.')
}

async function goodsIn(wh) {
  // purchase orders → approve → receive → put away
  const today = new Date()
  const expiryFor = (days) => new Date(today.getTime() + days * 86_400_000).toISOString().slice(0, 10)
  for (const s of SUPPLIERS) {
    const lines = PRODUCTS.filter((p) => p[3] === s.code)
    const po = await must('POST', '/api/v1/purchase-orders', {
      supplierCode: s.code, warehouseId: wh.id, note: 'Sample data',
      lines: lines.map(([sku, , , , , , perCarton, , cartons]) => ({ sku, uom: 'EA', qty: perCarton * cartons })),
    })
    const approved = await must('POST', `/api/v1/purchase-orders/${po.id}/approve`, { version: po.version })
    const receipt = await must('POST', '/api/v1/receipts', { poId: approved.id })
    for (const [sku, , , , , , perCarton, , cartons, expiry] of lines) {
      // dated goods arrive with a batch and an expiry; two products get a short date so Expiry colours show
      const extra = expiry ? { batchNo: `B${today.getMonth() + 1}${sku.slice(0, 3)}`, expiryDate: expiryFor(sku === 'MILK-1L' ? 20 : 240) } : {}
      await must('POST', `/api/v1/receipts/${receipt.id}/lines`, { code: sku, qty: perCarton * cartons, ...extra }, { 'Idempotency-Key': key() })
    }
    const detail = await must('GET', `/api/v1/receipts/${receipt.id}`)
    await must('POST', `/api/v1/receipts/${receipt.id}/close`, { version: detail.version, note: 'Sample data' })
    console.log(`Received ${po.number ?? 'PO'} from ${s.name}`)
  }

}

/** Puts away everything waiting in receiving, a pallet (or shelf load) at a time, into the suggested places. */
async function putAwayAll(wh) {
  let moved = 0
  for (;;) {
    const waiting = (await must('GET', `/api/v1/putaway?warehouse=${wh.id}&size=100`)).items
    if (waiting.length === 0) break
    let progress = false
    for (const wtg of waiting) {
      const spec = PRODUCTS.find((p) => p[0] === wtg.sku)
      const perPlace = spec ? spec[6] * Math.max(1, Math.round(spec[8] / 4)) : Number(wtg.qty) // about a quarter of the order per place
      // bulky goods: halve the load until a place can take it (a shelf place holds fewer fans than a pallet)
      let qty = Math.min(Number(wtg.qty), perPlace)
      let tips = []
      for (;;) {
        tips = await must('POST', '/api/v1/putaway/suggestions', { warehouseId: wh.id, productId: wtg.productId, batchNo: wtg.batchNo, qty })
        if (tips.length > 0 || qty <= 1) break
        qty = Math.max(1, Math.floor(qty / 2))
      }
      if (tips.length === 0) continue
      await must('POST', '/api/v1/putaway', { warehouseId: wh.id, productId: wtg.productId, batchNo: wtg.batchNo,
        expiryDate: wtg.expiryDate, qty, binCode: tips[0].code }, { 'Idempotency-Key': key() })
      moved++
      progress = true
    }
    if (!progress) {
      console.log('Some goods found no place and stay in receiving.')
      break
    }
  }
  if (moved > 0) console.log(`Put away ${moved} loads`)
}

async function salesOrders(wh) {
  // sales orders: three, two released for picking
  const orders = [
    ['CUS-KAN', [['RICE-5KG', 8], ['DHAL-1KG', 24], ['TEA-400G', 12]], true],
    ['CUS-GAL', [['SOAP-100', 96], ['DETG-1KG', 20], ['TISS-10', 12]], true],
    ['CUS-NEG', [['FAN-16', 3], ['BULB-9W', 50], ['KETL-17', 4]], false],
  ]
  // a customer that already has a sample order in DEMO1 is skipped, so a second run adds nothing twice
  const done = new Set((await must('GET', '/api/v1/sales-orders?size=100')).items
    .filter((o) => o.warehouseCode === 'DEMO1').map((o) => o.customerCode))
  let made = 0
  for (const [customerCode, lines, release] of orders) {
    if (done.has(customerCode)) continue
    made++
    const so = await must('POST', '/api/v1/sales-orders', { customerCode, warehouseId: wh.id, note: 'Sample data',
      lines: lines.map(([sku, qty]) => ({ sku, uom: 'EA', qty })) })
    if (release) await must('POST', `/api/v1/sales-orders/${so.id}/release`, { version: so.version })
  }
  console.log(made > 0 ? `Sales orders: ${made} added` : 'Sales orders: already there')
}

main().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
