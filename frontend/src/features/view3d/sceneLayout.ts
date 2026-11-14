import type { Scene } from './api'

/** One box in scene metres: centre, size, and turn about the vertical axis (radians, three.js sign). */
export interface BoxSpec {
  x: number
  y: number
  z: number
  w: number
  h: number
  d: number
  rotY: number
}

const M = 1 / 1000
const POST = 0.08 // upright post, 8 cm square
const BEAM = 0.05 // shelf beam / deck thickness
const GAP = 0.06 // space around a bin inside its slot

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

/**
 * Every box the viewer draws, grouped by mesh so each group is one instanced draw call:
 * posts (4 per rack), shelves (one deck per level), bins (one per bin, in the scene's bin order).
 */
export function buildInstances(scene: Scene): { posts: BoxSpec[]; shelves: BoxSpec[]; bins: BoxSpec[] } {
  const posts: BoxSpec[] = []
  const shelves: BoxSpec[] = []
  for (const r of scene.racks) {
    const w = r.widthMm * M
    const d = r.depthMm * M
    const h = r.heightMm * M
    for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]] as const) {
      const p = place(r, sx * (w / 2 - POST / 2), sz * (d / 2 - POST / 2))
      posts.push({ ...p, y: h / 2, w: POST, h, d: POST })
    }
    for (const level of r.levels) {
      const p = place(r, 0, 0)
      shelves.push({ ...p, y: level * M + BEAM / 2, w, h: BEAM, d })
    }
  }
  const bins: BoxSpec[] = []
  const { rack, level, pos } = scene.bins
  for (let i = 0; i < rack.length; i++) {
    const r = scene.racks[rack[i]!]!
    const w = r.widthMm * M
    const d = r.depthMm * M
    const slot = w / Math.max(1, r.binsPerLevel)
    const floorOf = r.levels[level[i]!]! * M + BEAM
    const ceiling = (r.levels[level[i]! + 1] ?? r.heightMm) * M
    const binH = Math.max(0.05, ceiling - floorOf - GAP)
    const p = place(r, -w / 2 + slot * (pos[i]! + 0.5), 0)
    bins.push({ ...p, y: floorOf + binH / 2, w: Math.max(0.05, slot - GAP), h: binH, d: Math.max(0.05, d - GAP) })
  }
  return { posts, shelves, bins }
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
