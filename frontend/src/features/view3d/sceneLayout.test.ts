import { describe, expect, it } from 'vitest'
import type { Scene } from './api'
import { buildInstances, centreOf, contentBox, wallParts } from './sceneLayout'

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

it('real cartons: as many per layer as fit (turned if more fit), layers up to the height, capped per place', async () => {
  const { cartonGrid, MAX_CARTONS_PER_PLACE } = await import('./sceneLayout')
  // 1.2 x 1.0 m pallet, 0.9 m of room: 600x400x300 cartons -> 2 x 2 per layer, 3 layers
  expect(cartonGrid(600, 400, 300, 12, 1.2, 1.0, 0.9)).toHaveLength(12)
  expect(cartonGrid(600, 400, 300, 99, 1.2, 1.0, 0.9)).toHaveLength(12) // no more than fit
  expect(cartonGrid(600, 400, 300, 5, 1.2, 1.0, 0.9)![4]!.y).toBeCloseTo(0.45) // the fifth starts layer 2
  // 300x500: as is 4 x 2 = 8 per layer, turned 2 x 3 = 6 -> as is
  expect(cartonGrid(300, 500, 200, 8, 1.2, 1.0, 0.25)).toHaveLength(8)
  // 1000x300 fits only turned (300 along the 1.2 m side, 1000 across the 1.0 m): 4 per layer
  expect(cartonGrid(1000, 300, 200, 4, 1.2, 1.0, 0.25)).toHaveLength(4)
  expect(cartonGrid(2000, 2000, 200, 1, 1.2, 1.0, 0.5)).toBeNull() // bigger than the pallet: the simple stack instead
  expect(cartonGrid(100, 100, 100, 1000, 1.2, 1.0, 1)).toHaveLength(MAX_CARTONS_PER_PLACE)
})

it('a place whose product has a carton size shows real cartons; others keep the simple stack', () => {
  const s = scene(0, [50, 50])
  s.cartons = [[600, 400, 300, 3], null]
  const p = buildInstances(s)
  expect(p.boxes.filter((c) => c.bin === 0)).toHaveLength(3) // real cartons
  expect(p.cartons.filter((c) => c.bin === 0)).toHaveLength(0)
  expect(p.cartons.filter((c) => c.bin === 1)).toHaveLength(1) // simple stack
  expect(p.boxes.find((c) => c.bin === 0)!.w).toBeCloseTo(0.588) // 600 mm less the hairline gap
})

describe('walls with real door openings (v2.1)', () => {
  // 20 m × 10 m floor, 8 m walls; one 1.2 m door in the middle of the top wall (y = 0)
  const floor = [0, 0, 20_000, 0, 20_000, 10_000, 0, 10_000]
  const door = { kind: 'DOOR' as const, label: 'Door 1', x: 10_000, y: 0, widthMm: 1200, heightMm: null }

  it('cuts the wall around the door: two pieces, a lintel, and a frame of real size', () => {
    const { walls, doors, docks } = wallParts(floor, [door], 8000)
    const top = walls.filter((w) => Math.abs(w.z) < 1e-9) // pieces along the top wall
    expect(top.map((w) => +w.w.toFixed(3)).sort()).toEqual([1.2, 9.4, 9.4]) // left, lintel, right
    const lintel = top.find((w) => Math.abs(w.w - 1.2) < 1e-9)!
    expect(lintel.y - lintel.h / 2).toBeCloseTo(2.1) // door height by default, up to the wall top
    expect(lintel.y + lintel.h / 2).toBeCloseTo(8)
    expect(walls).toHaveLength(3 + 3) // and the other three walls whole
    expect(doors).toHaveLength(3) // two posts and a top bar
    expect(Math.max(...doors.map((f) => f.y + f.h / 2))).toBeCloseTo(2.1)
    expect(docks).toHaveLength(0)
  })

  it('fills a door with a door leaf, and a dock with a shutter and a loading strip on the indoor side', () => {
    const d = wallParts(floor, [door], 8000)
    expect(d.leaves).toHaveLength(1)
    expect(d.leaves[0]!.w).toBeCloseTo(1.2 - 2 * 0.15) // inside the frame
    expect(d.leaves[0]!.y + d.leaves[0]!.h / 2).toBeCloseTo(2.1 - 0.15)
    expect(d.shutters).toHaveLength(0)
    const dock = { kind: 'DOCK' as const, label: 'Dock 1', x: 10_000, y: 0, widthMm: 3000, heightMm: 4500 }
    const k = wallParts(floor, [dock], 8000)
    expect(k.shutters).toHaveLength(1)
    expect(k.aprons).toHaveLength(1)
    expect(k.aprons[0]!.z).toBeGreaterThan(0) // the floor is below the top wall (y > 0): the strip is indoors
    expect(k.aprons[0]!.w).toBeCloseTo(3)
  })

  it('uses the typed height, and a door at a corner never makes a negative piece', () => {
    const dock = { kind: 'DOCK' as const, label: 'Dock 1', x: 200, y: 0, widthMm: 3000, heightMm: 4500 }
    const { walls, docks } = wallParts(floor, [dock], 8000)
    expect(walls.every((w) => w.w > 0 && w.h > 0)).toBe(true)
    expect(Math.max(...docks.map((f) => f.y + f.h / 2))).toBeCloseTo(4.5)
  })

  it('starts the camera on the racks, not the whole floor', () => {
    const big = [0, 0, 400_000, 0, 400_000, 400_000, 0, 400_000]
    const rack = { code: 'R01', zone: 'A', x: 10_000, y: 10_000, rotation: 0, widthMm: 2700, depthMm: 1100, heightMm: 6000 }
    const box = contentBox({ floor: big, racks: [rack] as never })
    expect(box.cx).toBeCloseTo(10)
    expect(box.size).toBeLessThan(20)
    expect(contentBox({ floor: big, racks: [] }).size).toBeCloseTo(400)
  })
})
