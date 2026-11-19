import { nearestOnOutline, pointInPolygon } from '@/features/layout/geometry'
import type { Scene } from './api'

/** Eye height, body radius (metres) and walking speed (metres per second) of the walk-through (v2.1). */
export const EYE = 1.7
export const RADIUS = 0.3
export const SPEED = 1.4

type Walls = Pick<Scene, 'floor' | 'racks'>

/** Where the walker is on the plan (mm) and which way they face (plan degrees, clockwise from plan-right). */
export interface Here {
  x: number
  y: number
  angle: number
}

/** The on-screen joystick: x right, y forward (-1..1); a push asks the 3D view (listening) for a frame. */
export class Stick {
  x = 0
  y = 0
  private wake: (() => void) | null = null
  push(x: number, y: number) {
    this.x = x
    this.y = y
    this.wake?.()
  }
  listen(wake: (() => void) | null) {
    this.wake = wake
  }
}

/** Is a walker standing at scene (x, z) metres inside a rack, outside the floor or against its edge? */
export function blocked(scene: Walls, x: number, z: number): boolean {
  const p = { x: x * 1000, y: z * 1000 }
  const r = RADIUS * 1000
  const outline: { x: number; y: number }[] = []
  for (let i = 0; i < scene.floor.length; i += 2) outline.push({ x: scene.floor[i]!, y: scene.floor[i + 1]! })
  if (!pointInPolygon(p, outline) || nearestOnOutline(outline, p).distance < r) return true
  // ponytail: tests every rack on each step; fine for hundreds of racks, add a grid index if huge layouts stutter.
  return scene.racks.some((k) => {
    const a = (k.rotation * Math.PI) / 180
    const dx = p.x - k.x
    const dy = p.y - k.y
    const lx = dx * Math.cos(a) + dy * Math.sin(a) // into the rack's own axes (x along it, y across)
    const ly = -dx * Math.sin(a) + dy * Math.cos(a)
    return Math.abs(lx) < k.widthMm / 2 + r && Math.abs(ly) < k.depthMm / 2 + r
  })
}

/** One move from (x, z) by (dx, dz); a move into something slides along it (x alone, then z alone). */
export function step(scene: Walls, x: number, z: number, dx: number, dz: number): { x: number; z: number } {
  if (!blocked(scene, x + dx, z + dz)) return { x: x + dx, z: z + dz }
  if (dx !== 0 && !blocked(scene, x + dx, z)) return { x: x + dx, z }
  if (dz !== 0 && !blocked(scene, x, z + dz)) return { x, z: z + dz }
  return { x, z }
}

/**
 * Where the walk starts, in scene metres, facing yaw (radians, 0 = plan-up / -z): 1.5 m inside the first door or
 * dock facing in; otherwise the free spot nearest the floor's middle facing plan-up.
 */
export function walkStart(scene: Pick<Scene, 'floor' | 'racks' | 'openings'>): { x: number; z: number; yaw: number } {
  const outline: { x: number; y: number }[] = []
  for (let i = 0; i < scene.floor.length; i += 2) outline.push({ x: scene.floor[i]!, y: scene.floor[i + 1]! })
  for (const o of scene.openings) {
    const a = (nearestOnOutline(outline, o).angle * Math.PI) / 180
    for (const s of [1, -1]) {
      const nx = -Math.sin(a) * s
      const ny = Math.cos(a) * s
      const x = (o.x + nx * 1500) / 1000
      const z = (o.y + ny * 1500) / 1000
      if (!blocked(scene, x, z)) return { x, z, yaw: Math.atan2(-nx, -ny) }
    }
  }
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  for (const p of outline) {
    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y)
  }
  const cx = (minX + maxX) / 2000
  const cz = (minY + maxY) / 2000
  const reach = Math.ceil(Math.max(maxX - minX, maxY - minY) / 1000)
  for (let ring = 0; ring <= reach; ring++) {
    for (let i = -ring; i <= ring; i++) {
      for (const [x, z] of [[cx + i, cz - ring], [cx + i, cz + ring], [cx - ring, cz + i], [cx + ring, cz + i]] as const) {
        if (!blocked(scene, x, z)) return { x, z, yaw: 0 }
      }
    }
  }
  return { x: cx, z: cz, yaw: 0 }
}
