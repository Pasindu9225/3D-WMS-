import { expect, it } from 'vitest'
import type { Scene } from './api'
import { buildInstances } from './sceneLayout'

const rack = (rotation: number): Scene['racks'][number] => ({
  code: 'R01', zone: 'A', x: 10_000, y: 5000, rotation, widthMm: 3000, depthMm: 1000, heightMm: 4000,
  binsPerLevel: 3, levels: [0, 2000],
})
const scene = (rotation: number): Scene => ({
  publishedAt: 'x', floor: [], openings: [], zones: [], racks: [rack(rotation)], flags: '00',
  bins: { ids: ['a', 'b'], codes: ['A-R01-01-01', 'A-R01-02-03'], rack: [0, 0], level: [0, 1], pos: [0, 2] },
})

it('puts 4 posts per rack, one shelf per level and one box per bin', () => {
  const { posts, shelves, bins } = buildInstances(scene(0))
  expect(posts).toHaveLength(4)
  expect(shelves.map((s) => s.y)).toEqual([0.025, 2.025])
  expect(bins).toHaveLength(2)
})

it('places bins by slot along the rack and on their level', () => {
  const [first, last] = buildInstances(scene(0)).bins
  // 3 m wide, 3 slots: centres at -1, 0, +1 m from the rack centre (10 m, 5 m)
  expect(first!.x).toBeCloseTo(9)
  expect(first!.z).toBeCloseTo(5)
  expect(first!.y).toBeCloseTo(0.05 + (2 - 0.05 - 0.06) / 2) // between the bottom deck and level 2
  expect(last!.x).toBeCloseTo(11)
  expect(last!.y).toBeGreaterThan(2)
})

it('turns with the rack: 90° clockwise on the plan sends the left end towards -z (plan up)', () => {
  const [first] = buildInstances(scene(90)).bins
  expect(first!.x).toBeCloseTo(10)
  expect(first!.z).toBeCloseTo(4) // plan y 5 m minus 1 m
  expect(first!.rotY).toBeCloseTo(-Math.PI / 2)
})
