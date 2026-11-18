import type { Scene } from './api'

/** One box in scene metres: centre, size, turn about the vertical axis (radians, three.js sign), optional tilt. */
export interface BoxSpec {
  x: number
  y: number
  z: number
  w: number
  h: number
  d: number
  rotY: number
  /** Turn about the rack's own length axis (radians), for diagonal bracing in an end frame. */
  tilt?: number
}

/** A box that belongs to one bin (index in the scene's bin order), so it can take that bin's colour. */
export interface BinBox extends BoxSpec {
  bin: number
}

const M = 1 / 1000
const POST = 0.09 // upright post, 9 cm
const BRACE = 0.035 // frame bracing tube
const BEAM_H = 0.11 // step beam height
const BEAM_D = 0.05 // step beam depth
const GAP = 0.06 // space around a bin inside its slot
const PALLET_H = 0.144 // a Euro pallet is 144 mm high
const LABEL = { w: 0.16, h: 0.05, d: 0.006 }

/**
 * Plan (x right, y down, clockwise degrees) to scene (x right, z = plan y, y up). A clockwise plan turn of θ
 * is a turn of -θ about the scene's y axis.
 */
function place(r: Scene['racks'][number], lx: number, lz: number) {
  const a = (r.rotation * Math.PI) / 180
  const cos = Math.cos(a)
  const sin = Math.sin(a)
  return { x: r.x * M + lx * cos - lz * sin, z: r.y * M + lx * sin + lz * cos, rotY: -a }
}

export const UPRIGHT = '#1d4ed8'
export const BEAM = '#f97316'

export interface SceneParts {
  /** Upright posts (2 per end frame) and their base plates. */
  posts: BoxSpec[]
  /** One colour per post, per brace, per beam / shelf: the rack type's own colours (v2.1). */
  postColors: string[]
  braceColors: string[]
  beamColors: string[]
  /** Horizontal and diagonal bracing of the end frames. */
  braces: BoxSpec[]
  /** Step beams, front and back, at every level above the floor. */
  beams: BoxSpec[]
  /** Shelving (SHELF style): one solid shelf per raised level. */
  shelves: BoxSpec[]
  shelfColors: string[]
  /** Floor storage (FLOOR style): a painted outline round each pallet position. */
  floorMarks: BoxSpec[]
  /** One white label plate per bin, on the beam under it (or on the floor in front of a floor position). */
  labels: BoxSpec[]
  /** A wooden pallet under every bin that holds stock. */
  pallets: BinBox[]
  /** The carton stack on each of those pallets; its height follows how full the bin is. */
  cartons: BinBox[]
  /** Bins that hold nothing: a faint box, only drawn in the colour modes. */
  empties: BinBox[]
  /** The full slot of every bin, in scene order: picking, search, fly-to and the highlight use these. */
  bins: BoxSpec[]
}

/**
 * Every box the viewer draws, grouped by kind so each kind is one instanced draw call (v2.1: real pallet racking).
 * fill (0-100, from the scene) decides which bins show a pallet with cartons and how tall the stack is.
 */
export function buildInstances(scene: Scene): SceneParts {
  const posts: BoxSpec[] = []
  const braces: BoxSpec[] = []
  const beams: BoxSpec[] = []
  const shelves: BoxSpec[] = []
  const postColors: string[] = []
  const braceColors: string[] = []
  const beamColors: string[] = []
  const shelfColors: string[] = []
  const floorMarks: BoxSpec[] = []
  for (const r of scene.racks) {
    if (r.style === 'FLOOR') { // pallets stacked on the floor: no uprights or beams, a painted outline per position
      const w = r.widthMm * M
      const d = r.depthMm * M
      const slot = w / Math.max(1, r.binsPerLevel)
      const line = 0.06
      for (let k = 0; k < r.binsPerLevel; k++) {
        const lx = -w / 2 + slot * (k + 0.5)
        for (const sz of [-1, 1]) floorMarks.push({ ...place(r, lx, (sz * d) / 2), y: 0.01, w: slot - 0.04, h: 0.01, d: line })
        for (const sx of [-1, 1]) floorMarks.push({ ...place(r, lx + (sx * (slot - 0.04)) / 2, 0), y: 0.01, w: line, h: 0.01, d })
      }
      continue
    }
    const up = r.uprightColor ?? UPRIGHT
    const beam = r.beamColor ?? BEAM
    const shelving = r.style === 'SHELF'
    const postsBefore = posts.length
    const bracesBefore = braces.length
    const w = r.widthMm * M
    const d = r.depthMm * M
    const h = r.heightMm * M
    for (const sx of [-1, 1]) { // the two end frames
      const ex = sx * (w / 2 - POST / 2)
      for (const sz of [-1, 1]) {
        const p = place(r, ex, sz * (d / 2 - POST / 2))
        posts.push({ ...p, y: h / 2, w: POST, h, d: POST })
        posts.push({ ...p, y: 0.005, w: POST * 1.8, h: 0.01, d: POST * 1.8 }) // base plate
      }
      // bracing: horizontals about every 1.2 m, diagonals zig-zagging between them
      const inner = Math.max(0.1, d - POST)
      const steps = Math.max(1, Math.round(h / 1.2))
      const step = h / steps
      const c = place(r, ex, 0)
      for (let k = 0; k <= steps; k++) {
        braces.push({ ...c, y: Math.min(h - 0.05, Math.max(0.15, k * step)), w: BRACE, h: BRACE, d: inner })
      }
      for (let k = 0; k < steps; k++) {
        const len = Math.hypot(inner, step)
        const tilt = Math.atan2(step, inner) * (k % 2 === 0 ? 1 : -1)
        braces.push({ ...c, y: (k + 0.5) * step, w: BRACE, h: BRACE, d: len, tilt })
      }
    }
    for (let k = postsBefore; k < posts.length; k++) postColors.push(up)
    for (let k = bracesBefore; k < braces.length; k++) braceColors.push(up)
    for (const level of r.levels) {
      if (level <= 0) continue // floor level: goods stand on the floor
      for (const sz of [-1, 1]) {
        const p = place(r, 0, sz * (d / 2 - BEAM_D / 2))
        beams.push({ ...p, y: level * M - BEAM_H / 2, w: Math.max(0.1, w - POST * 2), h: shelving ? BEAM_H / 2 : BEAM_H, d: BEAM_D })
        beamColors.push(beam)
      }
      if (shelving) { // a solid shelf between the beams
        shelves.push({ ...place(r, 0, 0), y: level * M - 0.015, w: Math.max(0.1, w - POST * 2), h: 0.03, d: Math.max(0.1, d - BEAM_D) })
        shelfColors.push(beam)
      }
    }
  }
  const labels: BoxSpec[] = []
  const pallets: BinBox[] = []
  const cartons: BinBox[] = []
  const empties: BinBox[] = []
  const bins: BoxSpec[] = []
  const { rack, level, pos } = scene.bins
  for (let i = 0; i < rack.length; i++) {
    const r = scene.racks[rack[i]!]!
    const w = r.widthMm * M
    const d = r.depthMm * M
    const slot = w / Math.max(1, r.binsPerLevel)
    const floorOf = r.levels[level[i]!]! * M
    const onFloor = r.style === 'FLOOR'
    const ceiling = (r.levels[level[i]! + 1] ?? r.heightMm) * M - (r.levels[level[i]! + 1] !== undefined && !onFloor ? BEAM_H : 0)
    const binH = Math.max(0.05, ceiling - floorOf - GAP)
    const lx = -w / 2 + slot * (pos[i]! + 0.5)
    const p = place(r, lx, 0)
    const box = { ...p, y: floorOf + binH / 2, w: Math.max(0.05, slot - GAP), h: binH, d: Math.max(0.05, d - GAP) }
    bins.push(box)
    if (onFloor && level[i] === 0) { // a plate lying on the floor in front of the position
      labels.push({ ...place(r, lx, d / 2 + 0.12), y: 0.006, w: LABEL.w * 1.5, h: 0.006, d: LABEL.h * 2 })
    } else if (!onFloor && floorOf > 0) {
      const front = place(r, lx, d / 2 + LABEL.d / 2)
      labels.push({ ...front, y: floorOf - BEAM_H / 2, ...LABEL })
    }
    const fill = scene.fill?.[i] ?? 0
    if (fill > 0) {
      const onPallet = r.style !== 'SHELF' // shelving: cartons straight on the shelf
      const pw = Math.min(box.w - 0.04, onPallet ? 1.2 : box.w)
      const pd = Math.min(box.d, onPallet ? 1.0 : box.d)
      const base = onPallet ? PALLET_H : 0
      if (onPallet) pallets.push({ ...p, y: floorOf + PALLET_H / 2, w: pw, h: PALLET_H, d: pd, bin: i })
      const room = Math.max(0.1, binH - base - 0.02)
      const share = fill <= 1 ? 0.4 : Math.min(1, Math.max(0.2, fill / 100)) // 1 % = goods of unknown size: show a modest stack
      const ch = room * share
      cartons.push({ ...p, y: floorOf + base + ch / 2, w: pw * 0.96, h: ch, d: pd * 0.96, bin: i })
    } else {
      empties.push({ ...box, bin: i })
    }
  }
  return { posts, postColors, braces, braceColors, beams, beamColors, shelves, shelfColors, floorMarks, labels, pallets, cartons, empties, bins }
}

/** Centre and size of the floor, for framing the camera. */
export function floorBox(floor: number[]) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (let i = 0; i < floor.length; i += 2) {
    minX = Math.min(minX, floor[i]!); maxX = Math.max(maxX, floor[i]!)
    minY = Math.min(minY, floor[i + 1]!); maxY = Math.max(maxY, floor[i + 1]!)
  }
  return { cx: ((minX + maxX) / 2) * M, cz: ((minY + maxY) / 2) * M, size: Math.max(maxX - minX, maxY - minY, 1000) * M }
}

/** Middle point of a polygon's corners (plan mm), for a zone's painted name. */
export function centreOf(flat: number[]): { x: number; y: number } {
  let x = 0
  let y = 0
  const n = flat.length / 2
  for (let i = 0; i < flat.length; i += 2) {
    x += flat[i]!
    y += flat[i + 1]!
  }
  return { x: n ? x / n : 0, y: n ? y / n : 0 }
}

/** A painted floor line along every edge of a polygon (plan mm), as thin boxes in scene metres. */
export function edgeStripes(flat: number[], width: number, height = 0.012): BoxSpec[] {
  const out: BoxSpec[] = []
  const n = flat.length / 2
  for (let i = 0; i < n; i++) {
    const ax = flat[i * 2]! * M
    const az = flat[i * 2 + 1]! * M
    const bx = flat[((i + 1) % n) * 2]! * M
    const bz = flat[((i + 1) % n) * 2 + 1]! * M
    const len = Math.hypot(bx - ax, bz - az)
    if (len < 0.01) continue
    // a box whose length (w) runs from a to b: turn = -atan2(dz, dx) in three.js's y-rotation sign
    out.push({ x: (ax + bx) / 2, y: height / 2 + 0.006, z: (az + bz) / 2, w: len, h: height, d: width, rotY: -Math.atan2(bz - az, bx - ax) })
  }
  return out
}
