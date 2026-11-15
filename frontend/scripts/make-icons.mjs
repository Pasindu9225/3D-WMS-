// Draws the app icon (a box on shelves, white on blue) as PNGs for the web app manifest. No image library:
// pixels are drawn into a buffer and written as PNG with Node's zlib. Run once: node scripts/make-icons.mjs
import { mkdirSync, writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'

const BLUE = [37, 99, 235]
const WHITE = [255, 255, 255]

function crc32(buf) {
  let c = ~0
  for (const b of buf) {
    c ^= b
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}

function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

function icon(size, maskable) {
  const px = Buffer.alloc(size * size * 3)
  const set = (x, y, c) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return
    px.set(c, (y * size + x) * 3)
  }
  const rect = (x0, y0, x1, y1, c) => {
    for (let y = Math.round(y0 * size); y < Math.round(y1 * size); y++)
      for (let x = Math.round(x0 * size); x < Math.round(x1 * size); x++) set(x, y, c)
  }
  rect(0, 0, 1, 1, BLUE)
  const m = maskable ? 0.2 : 0.12 // maskable icons keep the drawing inside the middle 80%
  const s = (v) => m + v * (1 - 2 * m)
  rect(s(0.05), s(0.9), s(0.95), s(0.97), WHITE) // floor shelf
  rect(s(0.05), s(0.45), s(0.95), s(0.52), WHITE) // middle shelf
  rect(s(0.05), s(0.05), s(0.13), s(0.97), WHITE) // left post
  rect(s(0.87), s(0.05), s(0.95), s(0.97), WHITE) // right post
  rect(s(0.22), s(0.62), s(0.48), s(0.9), WHITE) // boxes
  rect(s(0.54), s(0.68), s(0.78), s(0.9), WHITE)
  rect(s(0.3), s(0.2), s(0.62), s(0.45), WHITE)
  const raw = Buffer.alloc(size * (size * 3 + 1))
  for (let y = 0; y < size; y++) px.copy(raw, y * (size * 3 + 1) + 1, y * size * 3, (y + 1) * size * 3)
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // RGB
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))])
}

const dir = new URL('../public/icons/', import.meta.url)
mkdirSync(dir, { recursive: true })
for (const size of [192, 512]) {
  writeFileSync(new URL(`icon-${size}.png`, dir), icon(size, false))
  writeFileSync(new URL(`maskable-${size}.png`, dir), icon(size, true))
}
console.log('icons written')
