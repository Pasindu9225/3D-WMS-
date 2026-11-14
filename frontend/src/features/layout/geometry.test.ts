import { describe, expect, it } from 'vitest'
import { areaM2, insertOnEdge, nearestOnOutline, rackRect, snap } from './geometry'

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
