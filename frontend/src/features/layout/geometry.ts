import type { Point } from '@/features/warehouses/api'

/** Editor snap step: 0.1 m. Floor points are whole millimetres on the server. */
export const SNAP_MM = 100
export const MAX_MM = 1_000_000

export function snap(mm: number, step = SNAP_MM): number {
  return Math.min(MAX_MM, Math.max(0, Math.round(mm / step) * step))
}

export interface OnEdge {
  x: number
  y: number
  /** Edge from point[edge] to point[edge + 1] (wrapping). */
  edge: number
  /** Edge direction in degrees, for drawing a door along the wall. */
  angle: number
  distance: number
}

/** Closest point on the polygon outline to p: where a door or dock snaps to. */
export function nearestOnOutline(polygon: Point[], p: Point): OnEdge {
  let best: OnEdge = { x: p.x, y: p.y, edge: 0, angle: 0, distance: Infinity }
  for (let i = 0; i < polygon.length; i++) {
    const a = polygon[i]!
    const b = polygon[(i + 1) % polygon.length]!
    const dx = b.x - a.x
    const dy = b.y - a.y
    const len2 = dx * dx + dy * dy
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2))
    const x = a.x + t * dx
    const y = a.y + t * dy
    const distance = Math.hypot(p.x - x, p.y - y)
    if (distance < best.distance) {
      best = { x: Math.round(x), y: Math.round(y), edge: i, angle: (Math.atan2(dy, dx) * 180) / Math.PI, distance }
    }
  }
  return best
}

/** New corner where the user double-clicked an edge: inserted after point[edge], snapped. */
export function insertOnEdge(polygon: Point[], p: Point): Point[] {
  const hit = nearestOnOutline(polygon, p)
  const point = { x: snap(hit.x), y: snap(hit.y) }
  return [...polygon.slice(0, hit.edge + 1), point, ...polygon.slice(hit.edge + 1)]
}

export function bounds(points: Point[]): { minX: number; minY: number; maxX: number; maxY: number } {
  const xs = points.map((p) => p.x)
  const ys = points.map((p) => p.y)
  return { minX: Math.min(...xs), minY: Math.min(...ys), maxX: Math.max(...xs), maxY: Math.max(...ys) }
}

/** Area in m², shown to the planner as a sanity check (shoelace formula). */
export function areaM2(polygon: Point[]): number {
  let twice = 0
  for (let i = 0; i < polygon.length; i++) {
    const a = polygon[i]!
    const b = polygon[(i + 1) % polygon.length]!
    twice += a.x * b.y - b.x * a.y
  }
  return Math.abs(twice) / 2 / 1_000_000
}

/** Rack footprint on the floor: turned 90°/270° swaps width and depth; (x, y) is the centre. */
export function rackRect(rack: { x: number; y: number; rotation: number }, widthMm: number, depthMm: number) {
  const turned = rack.rotation === 90 || rack.rotation === 270
  const w = turned ? depthMm : widthMm
  const d = turned ? widthMm : depthMm
  return { x: rack.x - w / 2, y: rack.y - d / 2, width: w, height: d }
}

export type FloorShape = 'RECT' | 'L' | 'U' | 'T' | 'TRIANGLE' | 'OVAL'

/**
 * Common outlines inside a width x length box, in whole mm. Real warehouses are
 * rarely rectangles: L (corner cut away), U (loading yard in the middle), T (wide front, deep back).
 */
export function floorPreset(shape: FloorShape, w: number, l: number): Point[] {
  const p = (x: number, y: number) => ({ x: Math.round(x), y: Math.round(y) }) // whole mm: typed sizes stay exact
  switch (shape) {
    case 'L':
      return [p(0, 0), p(w * 0.6, 0), p(w * 0.6, l * 0.5), p(w, l * 0.5), p(w, l), p(0, l)]
    case 'U':
      return [p(0, 0), p(w / 3, 0), p(w / 3, l * 0.5), p((2 * w) / 3, l * 0.5), p((2 * w) / 3, 0), p(w, 0), p(w, l), p(0, l)]
    case 'T':
      return [p(0, 0), p(w, 0), p(w, l * 0.4), p(w * 0.65, l * 0.4), p(w * 0.65, l), p(w * 0.35, l), p(w * 0.35, l * 0.4),
        p(0, l * 0.4)]
    case 'TRIANGLE':
      return [p(w / 2, 0), p(w, l), p(0, l)]
    case 'OVAL': {
      // Stored as a 32-corner outline (layouts are polygons); whole mm so small ovals stay round.
      const n = 32
      return Array.from({ length: n }, (_, i) => {
        const a = (2 * Math.PI * i) / n
        return { x: Math.round(w / 2 + (w / 2) * Math.cos(a)), y: Math.round(l / 2 + (l / 2) * Math.sin(a)) }
      })
    }
    default:
      return [p(0, 0), p(w, 0), p(w, l), p(0, l)]
  }
}

/** True if any two non-neighbouring edges cross (the server refuses such outlines too). */
export function selfIntersects(polygon: Point[]): boolean {
  const n = polygon.length
  const cross = (a: Point, b: Point, c: Point) => (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
  const onSegment = (a: Point, b: Point, c: Point) =>
    Math.min(a.x, b.x) <= c.x && c.x <= Math.max(a.x, b.x) && Math.min(a.y, b.y) <= c.y && c.y <= Math.max(a.y, b.y)
  const intersect = (p1: Point, p2: Point, p3: Point, p4: Point) => {
    const d1 = cross(p3, p4, p1)
    const d2 = cross(p3, p4, p2)
    const d3 = cross(p1, p2, p3)
    const d4 = cross(p1, p2, p4)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
    return (d1 === 0 && onSegment(p3, p4, p1)) || (d2 === 0 && onSegment(p3, p4, p2))
      || (d3 === 0 && onSegment(p1, p2, p3)) || (d4 === 0 && onSegment(p1, p2, p4))
  }
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      if (j === i + 1 || (i === 0 && j === n - 1)) continue // neighbours share a corner
      if (intersect(polygon[i]!, polygon[(i + 1) % n]!, polygon[j]!, polygon[(j + 1) % n]!)) return true
    }
  }
  return false
}

/** Ray-casting point-in-polygon; points on an edge may go either way (good enough for live hints). */
export function pointInPolygon(p: Point, polygon: Point[]): boolean {
  let inside = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i]!
    const b = polygon[j]!
    if (a.y > p.y !== b.y > p.y && p.x < ((b.x - a.x) * (p.y - a.y)) / (b.y - a.y) + a.x) inside = !inside
  }
  return inside
}

type Box = { x: number; y: number; width: number; height: number }

/**
 * Rectangle fully inside the polygon: centre-of-edge samples and corners nudged 1 mm inward are inside,
 * and no polygon corner pokes into the rectangle.
 * ponytail: live hint only, the server's java.awt Area check is the authority on save.
 */
export function rectInPolygon(r: Box, polygon: Point[]): boolean {
  const e = 1
  const x0 = r.x + e, y0 = r.y + e, x1 = r.x + r.width - e, y1 = r.y + r.height - e
  const samples = [{ x: x0, y: y0 }, { x: x1, y: y0 }, { x: x1, y: y1 }, { x: x0, y: y1 },
    { x: (x0 + x1) / 2, y: y0 }, { x: x1, y: (y0 + y1) / 2 }, { x: (x0 + x1) / 2, y: y1 }, { x: x0, y: (y0 + y1) / 2 }]
  if (!samples.every((s) => pointInPolygon(s, polygon))) return false
  return !polygon.some((p) => p.x > r.x && p.x < r.x + r.width && p.y > r.y && p.y < r.y + r.height)
}

export type RackStatus = 'bad' | 'warn'

/**
 * Live rack checks, same rules as the server: overlapping racks and racks outside their zone are
 * errors; racks facing each other closer than the minimum aisle are warnings (0 gap = back-to-back, fine).
 * ponytail: O(n²) pairs, fine for the 2,000-rack cap.
 */
export function rackStatuses(
  doc: { zones: { code: string; polygon: Point[] }[]; racks: { code: string; templateId: string; zone: string; x: number; y: number; rotation: number }[]; settings: { minAisleMm: number } },
  templates: Record<string, { widthMm: number; depthMm: number }>,
): (RackStatus | undefined)[] {
  const status: (RackStatus | undefined)[] = doc.racks.map(() => undefined)
  const rects = doc.racks.map((r) => {
    const t = templates[r.templateId]
    return t ? rackRect(r, t.widthMm, t.depthMm) : null
  })
  const zones = new Map(doc.zones.map((z) => [z.code, z.polygon]))
  rects.forEach((rect, i) => {
    const zone = zones.get(doc.racks[i]!.zone)
    if (!rect || !zone || !rectInPolygon(rect, zone)) status[i] = 'bad'
  })
  for (let i = 0; i < rects.length; i++) {
    const a = rects[i]
    if (!a) continue
    for (let j = i + 1; j < rects.length; j++) {
      const b = rects[j]
      if (!b) continue
      const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)
      const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y)
      if (overlapX > 0 && overlapY > 0) {
        status[i] = status[j] = 'bad'
        continue
      }
      const gap = overlapY > 0 ? -overlapX : overlapX > 0 ? -overlapY : -1
      if (gap > 0 && gap < doc.settings.minAisleMm) {
        status[i] ??= 'warn'
        status[j] ??= 'warn'
      }
    }
  }
  return status
}

/** Next free code like Z1 or R01: prefix plus the lowest unused number. */
export function nextCode(prefix: string, used: string[], pad = 1): string {
  const taken = new Set(used)
  for (let n = 1; ; n++) {
    const code = prefix + String(n).padStart(pad, '0')
    if (!taken.has(code)) return code
  }
}
