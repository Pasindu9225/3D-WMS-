import { describe, expect, it } from 'vitest'
import { areaM2, floorPreset, insertOnEdge, nearestOnOutline, nextCode, rackPolygon, rackRect, rackStatuses, rectInPolygon, selfIntersects, snap } from './geometry'

const square = [
  { x: 0, y: 0 },
  { x: 10_000, y: 0 },
  { x: 10_000, y: 10_000 },
  { x: 0, y: 10_000 },
]

describe('geometry', () => {
  it('snaps to 0.1 m and stays on the plan', () => {
    expect(snap(1249)).toBe(1200)
    expect(snap(1251)).toBe(1300)
    expect(snap(-400)).toBe(0)
  })

  it('finds the nearest wall point and its direction', () => {
    const hit = nearestOnOutline(square, { x: 4000, y: 9000 })
    expect(hit).toMatchObject({ x: 4000, y: 10_000, edge: 2, distance: 1000 })
    expect(Math.abs(hit.angle)).toBe(180) // bottom edge runs right to left
    expect(nearestOnOutline(square, { x: 10_500, y: 3000 })).toMatchObject({ x: 10_000, y: 3000, edge: 1 })
  })

  it('inserts a corner after the clicked edge', () => {
    const out = insertOnEdge(square, { x: 5030, y: 40 })
    expect(out).toHaveLength(5)
    expect(out[1]).toEqual({ x: 5000, y: 0 })
    expect(out[2]).toEqual({ x: 10_000, y: 0 })
  })

  it('measures floor area in square metres', () => {
    expect(areaM2(square)).toBe(100)
  })

  it('swaps width and depth when a rack is turned', () => {
    expect(rackRect({ x: 5000, y: 5000, rotation: 0 }, 2700, 1100)).toEqual({ x: 3650, y: 4450, width: 2700, height: 1100 })
    expect(rackRect({ x: 5000, y: 5000, rotation: 90 }, 2700, 1100)).toEqual({ x: 4450, y: 3650, width: 1100, height: 2700 })
  })
})

describe('floor shapes', () => {
  it('builds L, U and T outlines that never cross themselves', () => {
    for (const shape of ['RECT', 'L', 'U', 'T'] as const) {
      expect(selfIntersects(floorPreset(shape, 40_000, 20_000)), shape).toBe(false)
    }
    expect(floorPreset('L', 40_000, 20_000)).toHaveLength(6)
    expect(areaM2(floorPreset('L', 40_000, 20_000))).toBe(640) // 800 m² minus the 16 x 10 m corner
    expect(areaM2(floorPreset('U', 30_000, 20_000))).toBe(500) // minus the 10 x 10 m yard
  })

  it('spots crossing outlines', () => {
    const bowTie = [
      { x: 0, y: 0 },
      { x: 10_000, y: 10_000 },
      { x: 10_000, y: 0 },
      { x: 0, y: 10_000 },
    ]
    expect(selfIntersects(bowTie)).toBe(true)
    expect(selfIntersects(square)).toBe(false)
  })
})

describe('live rack checks (same rules as the server)', () => {
  const t = { T: { widthMm: 2000, depthMm: 1000 } }
  const zone = { code: 'Z1', polygon: [{ x: 0, y: 0 }, { x: 20_000, y: 0 }, { x: 20_000, y: 20_000 }, { x: 0, y: 20_000 }] }
  const rack = (code: string, x: number, y: number, z = 'Z1') => ({ code, templateId: 'T', zone: z, x, y, rotation: 0 })
  const check = (racks: ReturnType<typeof rack>[]) => rackStatuses({ zones: [zone], racks, settings: { minAisleMm: 1200 } }, t)

  it('flags overlaps and narrow aisles, but not back-to-back racks', () => {
    expect(check([rack('A', 2000, 2000), rack('B', 3000, 2000)])).toEqual(['bad', 'bad']) // overlap
    expect(check([rack('A', 2000, 2000), rack('B', 2000, 3000)])).toEqual([undefined, undefined]) // back to back
    expect(check([rack('A', 2000, 2000), rack('B', 2000, 4000)])).toEqual(['warn', 'warn']) // 1 m aisle
    expect(check([rack('A', 2000, 2000), rack('B', 2000, 5000)])).toEqual([undefined, undefined]) // 2 m aisle
  })

  it('flags racks outside or partly outside their zone, or in a missing zone', () => {
    expect(check([rack('A', 500, 2000)])).toEqual(['bad']) // sticks out on the left
    expect(check([rack('A', 2000, 2000, 'NOPE')])).toEqual(['bad'])
  })

  it('knows a rectangle in the cut-away corner of an L-shaped zone is outside', () => {
    const l = floorPreset('L', 20_000, 10_000) // top-right corner removed
    expect(rectInPolygon({ x: 1000, y: 1000, width: 2000, height: 1000 }, l)).toBe(true)
    expect(rectInPolygon({ x: 15_000, y: 1000, width: 2000, height: 1000 }, l)).toBe(false)
    expect(rectInPolygon({ x: 11_000, y: 4000, width: 2000, height: 2000 }, l)).toBe(false) // across the inner corner
  })

  it('picks the next free code', () => {
    expect(nextCode('R', ['R01', 'R03'], 2)).toBe('R02')
    expect(nextCode('Z', [])).toBe('Z1')
  })
})

describe('angled and resized racks (same cases as the server test)', () => {
  const t = { T: { widthMm: 2700, depthMm: 1100 } }
  const zone = { code: 'A', polygon: [{ x: 0, y: 0 }, { x: 40_000, y: 0 }, { x: 40_000, y: 20_000 }, { x: 0, y: 20_000 }] }
  const rack = (code: string, x: number, y: number, rotation: number, widthMm?: number) =>
    ({ code, templateId: 'T', zone: 'A', x, y, rotation, widthMm })
  const check = (racks: ReturnType<typeof rack>[]) => rackStatuses({ zones: [zone], racks, settings: { minAisleMm: 1200 } }, t)

  it('turns corners clockwise around the centre', () => {
    const p = rackPolygon({ x: 0, y: 0, rotation: 90 }, 2000, 1000)
    expect(p.map((c) => [Math.round(c.x), Math.round(c.y)])).toEqual([[500, -1000], [500, 1000], [-500, 1000], [-500, -1000]])
  })

  it('checks angled racks by their real outline', () => {
    expect(check([rack('R1', 10_000, 10_000, 45)])).toEqual([undefined])
    expect(check([rack('R1', 10_000, 10_000, 45), rack('R2', 12_400, 12_400, 45)])).toEqual(['warn', 'warn'])
    expect(check([rack('R1', 10_000, 10_000, 45), rack('R3', 11_000, 11_000, 45)])).toEqual(['bad', 'bad'])
    expect(check([rack('R4', 1200, 5000, 30)])).toEqual(['bad'])
  })

  it('uses a resized rack’s own width', () => {
    expect(check([rack('R1', 5000, 5000, 0, 4000), rack('R2', 8300, 5000, 0)])).toEqual(['bad', 'bad'])
    expect(check([rack('R1', 5000, 5000, 0), rack('R2', 8300, 5000, 0)])).toEqual(['warn', 'warn'])
  })
})
