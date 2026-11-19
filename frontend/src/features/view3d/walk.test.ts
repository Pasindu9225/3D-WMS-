import { expect, it } from 'vitest'
import { blocked, step, walkStart } from './walk'

const rack = (x: number, y: number, rotation = 0) => ({ code: 'R', zone: 'A', x, y, rotation, widthMm: 4000, depthMm: 1000, heightMm: 3000, binsPerLevel: 2, levels: [0] })
// a 20 m × 10 m floor; one rack 4 m long, 1 m deep, centred at (10 m, 5 m)
const scene = { floor: [0, 0, 20000, 0, 20000, 10000, 0, 10000], racks: [rack(10000, 5000)], openings: [] }

it('lets you stand in the aisle but not in a rack, against it, or outside the floor', () => {
  expect(blocked(scene, 10, 7)).toBe(false)
  expect(blocked(scene, 10, 5)).toBe(true)
  expect(blocked(scene, 11.9, 5.7)).toBe(true) // within the 0.3 m body radius of the rack face
  expect(blocked(scene, 25, 5)).toBe(true)
  expect(blocked(scene, 0.1, 5)).toBe(true) // against the outer wall
})

it('turns the rack shape with the rack', () => {
  const turned = { ...scene, racks: [rack(10000, 5000, 90)] }
  expect(blocked(turned, 10, 6.5)).toBe(true) // now 4 m along plan y
  expect(blocked(turned, 11.5, 5)).toBe(false)
})

it('slides along a rack instead of stopping dead', () => {
  const to = step(scene, 9, 6.2, 0.5, -0.5) // diagonal into the rack face
  expect(to).toEqual({ x: 9.5, z: 6.2 })
})

it('starts inside the first door, facing in, or at a free spot near the middle', () => {
  const door = walkStart({ ...scene, openings: [{ kind: 'DOOR', x: 10000, y: 10000, widthMm: 1000, label: 'D1' }] as never })
  expect(door.x).toBeCloseTo(10)
  expect(door.z).toBeCloseTo(8.5)
  expect(Math.abs(door.yaw)).toBeCloseTo(0) // looking plan-up, into the building
  const mid = walkStart(scene)
  expect(blocked(scene, mid.x, mid.z)).toBe(false)
})
