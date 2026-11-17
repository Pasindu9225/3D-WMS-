import { expect, it } from 'vitest'
import type { Scene } from './api'
import { droneTour, LOW } from './drone'
import type { PickRoute } from './route'

const rack = (code: string, x: number, y: number, rotation = 0) => ({ code, zone: 'A', x, y, rotation, widthMm: 2700, depthMm: 1100, heightMm: 3000, binsPerLevel: 3, levels: [0, 1500] })
const scene = {
  floor: [0, 0, 40000, 0, 40000, 20000, 0, 20000],
  racks: [rack('R03', 35000, 15000), rack('R01', 5000, 5000), rack('R02', 20000, 10000, 90)],
} as unknown as Scene

it('circles high, flies low past every rack, and ends high looking down', () => {
  const tour = droneTour(scene)
  expect(tour[0]!.pos.y).toBeGreaterThanOrEqual(8)
  const last = tour.at(-1)!
  expect(last.pos.y).toBeGreaterThan(40) // 1.25 × the 40 m floor
  expect(last.look).toEqual({ x: 20, y: 0, z: 10 })
  const low = tour.filter((k) => k.pos.y === LOW)
  expect(low).toHaveLength(6) // two points along each of three racks
  for (const r of scene.racks) { // every rack is looked at
    expect(low.some((k) => Math.hypot(k.look.x - r.x / 1000, k.look.z - r.y / 1000) <= 1.4)).toBe(true)
  }
  // the camera stays near the floor (the circle is a little wider than the floor)
  for (const k of tour) {
    expect(k.pos.x).toBeGreaterThan(-20)
    expect(k.pos.x).toBeLessThan(60)
    expect(k.pos.z).toBeGreaterThan(-25)
    expect(k.pos.z).toBeLessThan(45)
  }
})

it('visits racks nearest first from where the circle ends', () => {
  const tour = droneTour(scene)
  const low = tour.filter((k) => k.pos.y === LOW)
  // the circle ends at angle 0: east of the floor, so the east rack R03 comes first and the west rack R01 last
  expect(low[0]!.look.x).toBeGreaterThan(30)
  expect(low.at(-1)!.look.x).toBeLessThan(10)
})

it('follows a pick route stop by stop when one is shown', () => {
  const route: PickRoute = { warehouseId: 'w', startX: 1000, startY: 1000, metres: 50, stops: [
    { step: 1, x: 5000, y: 5000, binCodes: [] }, { step: 2, x: 20000, y: 10000, binCodes: [] }] }
  const tour = droneTour(scene, route)
  const low = tour.filter((k) => k.pos.y === LOW).map((k) => [k.pos.x, k.pos.z])
  expect(low).toEqual([[1, 1], [5, 5], [20, 10], [1, 1]]) // start, the stops in order, back to start
  expect(tour[1]!.look).toMatchObject({ x: 5, z: 5 }) // looking ahead to the next stop
})

it('an empty layout still gets a short flight round the floor', () => {
  const tour = droneTour({ ...scene, racks: [] } as Scene)
  expect(tour.length).toBe(14) // 13 circle points and the finish
})
