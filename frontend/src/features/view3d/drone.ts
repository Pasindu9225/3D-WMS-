import type { Scene } from './api'
import type { Point3 } from './camera'
import type { PickRoute } from './route'
import { floorBox } from './sceneLayout'

const M = 1 / 1000
/** Height the drone flies along the racks and a pick route (metres). */
export const LOW = 3
/** Metres per second at 1× speed. */
export const DRONE_SPEED = 6

/** One point of the flight: where the camera is and what it looks at (scene metres: x, y up, z = plan y). */
export interface Keyframe {
  pos: Point3
  look: Point3
}

/**
 * The drone's flight (v2.1). With a pick route on screen it follows the walk; otherwise it circles the whole floor,
 * then flies along every rack nearest-first, and ends high above the floor looking down. Pure: the canvas turns the
 * keyframes into smooth curves.
 */
export function droneTour(scene: Scene, route?: PickRoute | null): Keyframe[] {
  const box = floorBox(scene.floor)
  const centre = { x: box.cx, y: 0, z: box.cz }
  const high = Math.max(8, box.size * 0.6)
  const out: Keyframe[] = []
  if (route && route.stops.length > 0) {
    const pts = [{ x: route.startX, y: route.startY }, ...route.stops.map((s) => ({ x: s.x, y: s.y })), { x: route.startX, y: route.startY }]
      .map((p) => ({ x: p.x * M, z: p.y * M }))
    out.push({ pos: { x: pts[0]!.x, y: high, z: pts[0]!.z + 0.01 }, look: { x: pts[0]!.x, y: 0, z: pts[0]!.z } })
    pts.forEach((p, i) => {
      const next = pts[Math.min(i + 1, pts.length - 1)]!
      out.push({ pos: { x: p.x, y: LOW, z: p.z }, look: { x: next.x, y: 1, z: next.z + (next === p ? 0.01 : 0) } })
    })
  } else {
    // 1. once around the floor
    const radius = box.size * 0.75
    for (let i = 0; i <= 12; i++) {
      const a = (i / 12) * Math.PI * 2
      out.push({ pos: { x: box.cx + radius * Math.cos(a), y: high, z: box.cz + radius * Math.sin(a) }, look: centre })
    }
    // 2. along every rack, nearest first from where the circle ended
    const left = scene.racks.map((r) => r)
    let at = out[out.length - 1]!.pos
    while (left.length > 0) {
      let best = 0
      for (let i = 1; i < left.length; i++) {
        if (dist(at, left[i]!) < dist(at, left[best]!)) best = i
      }
      const r = left.splice(best, 1)[0]!
      const a = (r.rotation * Math.PI) / 180
      const along = { x: Math.cos(a), z: Math.sin(a) } // the rack's length (its local x)
      const front = { x: -Math.sin(a), z: Math.cos(a) } // its face (local z)
      const half = (r.widthMm * M) / 2
      const off = (r.depthMm * M) / 2 + 2
      const midH = Math.max(1, (r.heightMm * M) / 2)
      const c = { x: r.x * M, z: r.y * M }
      for (const s of [-1, 1]) {
        const face = { x: c.x + along.x * half * s, y: midH, z: c.z + along.z * half * s }
        out.push({ pos: { x: face.x + front.x * off, y: LOW, z: face.z + front.z * off }, look: face })
      }
      at = out[out.length - 1]!.pos
    }
  }
  // 3. finish high above the floor, looking down (like Top view)
  out.push({ pos: { x: box.cx, y: box.size * 1.25, z: box.cz + 0.01 }, look: centre })
  return out
}

function dist(p: Point3, r: { x: number; y: number }): number {
  return Math.hypot(p.x - r.x * M, p.z - r.y * M)
}
