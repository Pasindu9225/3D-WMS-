import { expect, it } from 'vitest'
import type { Scene } from './api'
import { buildInstances, centreOf } from './sceneLayout'

const rack = (rotation: number): Scene['racks'][number] => ({
  code: 'R01', zone: 'A', x: 10_000, y: 5000, rotation, widthMm: 3000, depthMm: 1000, heightMm: 4000,
  binsPerLevel: 3, levels: [0, 2000],
})
const scene = (rotation: number, fill?: number[]): Scene => ({
  publishedAt: 'x', floor: [], openings: [], zones: [], racks: [rack(rotation)], flags: '00', fill,
  bins: { ids: ['a', 'b'], codes: ['A-R01-01-01', 'A-R01-02-03'], rack: [0, 0], level: [0, 1], pos: [0, 2] },
})

it('builds real pallet racking: 2 end frames with posts, base plates and bracing; front and back beams per raised level', () => {
  const { posts, braces, beams, labels, bins } = buildInstances(scene(0))
  expect(posts).toHaveLength(8) // 4 posts + 4 base plates
  expect(braces.length).toBeGreaterThanOrEqual(2 * (3 + 1 + 3)) // 4 m high: about 3 steps; horizontals + diagonals, both ends
  expect(braces.some((b) => b.tilt !== undefined && b.tilt !== 0)).toBe(true) // diagonals lean
  expect(beams).toHaveLength(2) // only the 2 m level has beams (the floor level stands on the floor)
  expect(beams.every((b) => Math.abs(b.y - (2 - 0.055)) < 1e-9)).toBe(true) // the beam top is the level
  expect(labels).toHaveLength(1) // a label on the beam under the raised bin only
  expect(bins).toHaveLength(2)
})

it('shows a pallet with a carton stack only where there is stock; taller when fuller', () => {
  const empty = buildInstances(scene(0, [0, 0]))
  expect(empty.pallets).toHaveLength(0)
  expect(empty.empties.map((e) => e.bin)).toEqual([0, 1])
  const half = buildInstances(scene(0, [50, 0]))
  const full = buildInstances(scene(0, [100, 0]))
  expect(half.pallets).toHaveLength(1)
  expect(half.pallets[0]!.bin).toBe(0)
  expect(half.pallets[0]!.y).toBeCloseTo(0.072) // on the floor: half the 144 mm pallet height
  expect(half.empties.map((e) => e.bin)).toEqual([1])
  expect(full.cartons[0]!.h).toBeCloseTo(half.cartons[0]!.h * 2)
  const unknown = buildInstances(scene(0, [1, 0])) // 1 % means goods of unknown size: a modest stack, not a sliver
  expect(unknown.cartons[0]!.h).toBeGreaterThan(half.cartons[0]!.h * 0.7)
})

it('places bins by slot along the rack and on their level', () => {
  const [first, last] = buildInstances(scene(0)).bins
  // 3 m wide, 3 slots: centres at -1, 0, +1 m from the rack centre (10 m, 5 m)
  expect(first!.x).toBeCloseTo(9)
  expect(first!.z).toBeCloseTo(5)
  expect(first!.y).toBeCloseTo((2 - 0.11 - 0.06) / 2) // from the floor up to under the 2 m beam
  expect(last!.x).toBeCloseTo(11)
  expect(last!.y).toBeGreaterThan(2)
})

it('turns with the rack: 90° clockwise on the plan sends the left end towards -z (plan up)', () => {
  const [first] = buildInstances(scene(90)).bins
  expect(first!.x).toBeCloseTo(10)
  expect(first!.z).toBeCloseTo(4) // plan y 5 m minus 1 m
  expect(first!.rotY).toBeCloseTo(-Math.PI / 2)
})

it('finds the middle of a zone for its painted name', () => {
  expect(centreOf([0, 0, 4000, 0, 4000, 2000, 0, 2000])).toEqual({ x: 2000, y: 1000 })
})

it('paints a line along each edge of a zone', async () => {
  const { edgeStripes } = await import('./sceneLayout')
  const lines = edgeStripes([0, 0, 4000, 0, 4000, 2000, 0, 2000], 0.1)
  expect(lines).toHaveLength(4)
  expect(lines[0]).toMatchObject({ x: 2, z: 0, w: 4, d: 0.1 }) // top edge, 4 m long
  expect(lines[1]!.w).toBeCloseTo(2) // right edge, 2 m
  expect(Math.abs(lines[1]!.rotY)).toBeCloseTo(Math.PI / 2)
})

it('draws each rack in its own colours; shelving gets solid shelves and no pallets', () => {
  const s = scene(0, [60, 60])
  s.racks[0] = { ...s.racks[0]!, style: 'SHELF', uprightColor: '#22c55e', beamColor: '#64748b' }
  const parts = buildInstances(s)
  expect(new Set(parts.postColors)).toEqual(new Set(['#22c55e']))
  expect(new Set(parts.beamColors)).toEqual(new Set(['#64748b']))
  expect(parts.shelves).toHaveLength(1) // the raised level
  expect(parts.pallets).toHaveLength(0)
  expect(parts.cartons).toHaveLength(2)
  expect(parts.cartons[0]!.y - parts.cartons[0]!.h / 2).toBeCloseTo(0) // straight on the floor, no pallet under it
  const classic = buildInstances(scene(0, [60, 60]))
  expect(classic.shelves).toHaveLength(0)
  expect(classic.pallets).toHaveLength(2)
  expect(classic.postColors[0]).toBe('#1d4ed8')
})

it('floor storage: no uprights or beams, an outline per position, pallets stacked on each other', () => {
  const s: Scene = {
    publishedAt: 'x', floor: [], openings: [], zones: [], flags: '000', fill: [80, 80, 0],
    racks: [{ code: '01', zone: 'F', x: 10_000, y: 5000, rotation: 0, widthMm: 2600, depthMm: 1200, heightMm: 3000,
      binsPerLevel: 2, levels: [0, 1000, 2000], style: 'FLOOR' }],
    bins: { ids: ['a', 'b', 'c'], codes: ['F-01-01-01', 'F-01-02-01', 'F-01-01-02'], rack: [0, 0, 0], level: [0, 1, 0], pos: [0, 0, 1] },
  }
  const p = buildInstances(s)
  expect(p.posts).toHaveLength(0)
  expect(p.beams).toHaveLength(0)
  expect(p.braces).toHaveLength(0)
  expect(p.floorMarks).toHaveLength(8) // 4 painted lines round each of the 2 positions
  expect(p.pallets.map((x) => x.y)).toEqual([0.072, 1.072]) // on the floor, and on top of the one below (1 m stacking height)
  expect(p.labels).toHaveLength(2) // floor plates in front of the 2 ground positions only
  expect(p.labels.every((l) => l.y < 0.01)).toBe(true)
})
