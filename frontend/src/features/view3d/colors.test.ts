import { expect, it } from 'vitest'
import type { Scene } from './api'
import { binColors, EXPIRY_COLOR, expiryBand, legend, STATUS_COLOR, statusOf, ZONE_COLOR } from './colors'

const scene = {
  zones: [{ code: 'A', type: 'PICKING', name: 'Pick', polygon: [] }, { code: 'B', type: 'COLD', name: 'Cold', polygon: [] }],
  racks: [{ zone: 'A' }, { zone: 'B' }],
  bins: { rack: [0, 0, 1, 1] },
  flags: '0000',
} as unknown as Scene

it('works out a bin status: blocked first, then fill, then pick face', () => {
  expect(statusOf(1 | 2, 0.5)).toBe('BLOCKED')
  expect(statusOf(0, 1)).toBe('FULL')
  expect(statusOf(2, 0.3)).toBe('PARTLY')
  expect(statusOf(2)).toBe('PICK_FACE')
  expect(statusOf(0)).toBe('EMPTY')
})

it('colours bins by zone type or by status from the live flags', () => {
  expect(binColors(scene, 'zone', '0000')).toEqual([ZONE_COLOR.PICKING, ZONE_COLOR.PICKING, ZONE_COLOR.COLD, ZONE_COLOR.COLD])
  expect(binColors(scene, 'status', '0123')).toEqual([STATUS_COLOR.EMPTY, STATUS_COLOR.BLOCKED, STATUS_COLOR.PICK_FACE, STATUS_COLOR.BLOCKED])
})

it('lists only the zone types present, or every status', () => {
  expect(legend(scene, 'zone').map((l) => l.key)).toEqual(['PICKING', 'COLD'])
  expect(legend(scene, 'status')).toHaveLength(5)
})

it('colours bins by how full they are once stock is recorded; blocked still wins', () => {
  const stocked = { ...scene, fill: [0, 40, 100, 100] }
  expect(binColors(stocked, 'status', '0001')).toEqual([STATUS_COLOR.EMPTY, STATUS_COLOR.PARTLY, STATUS_COLOR.FULL, STATUS_COLOR.BLOCKED])
})

it('colours bins by their soonest expiry: expired, 30, 60, 90 days, later, nothing dated', () => {
  expect([-1, 0, 30, 31, 60, 61, 90, 91, null, undefined].map(expiryBand))
    .toEqual(['EXPIRED', 'D30', 'D30', 'D60', 'D60', 'D90', 'D90', 'LATER', 'NONE', 'NONE'])
  const dated = { ...scene, expiryDays: [-2, 10, 120, null] }
  expect(binColors(dated, 'expiry', '0000')).toEqual([EXPIRY_COLOR.EXPIRED, EXPIRY_COLOR.D30, EXPIRY_COLOR.LATER, EXPIRY_COLOR.NONE])
  expect(legend(scene, 'expiry')).toHaveLength(6)
})

it('pick heat (v1.2): busiest bin red, unpicked grey, steps by share of the busiest', async () => {
  const { HEAT_COLOR, heatRanges, heatStep } = await import('./colors')
  expect(heatStep(0, 20)).toBe(0)
  expect(heatStep(1, 20)).toBe(1)
  expect(heatStep(4, 20)).toBe(1)
  expect(heatStep(5, 20)).toBe(2)
  expect(heatStep(20, 20)).toBe(5)
  const hot = { ...scene, picks: [0, 3, 12, 20] } as unknown as Scene
  expect(binColors(hot, 'heat', '0000')).toEqual([HEAT_COLOR[0], HEAT_COLOR[1], HEAT_COLOR[3], HEAT_COLOR[5]])
  expect(heatRanges(20)).toEqual([{ step: 1, from: 1, to: 4 }, { step: 2, from: 5, to: 8 }, { step: 3, from: 9, to: 12 },
    { step: 4, from: 13, to: 16 }, { step: 5, from: 17, to: 20 }])
  expect(heatRanges(2)).toEqual([{ step: 3, from: 1, to: 1 }, { step: 5, from: 2, to: 2 }]) // few picks: only reachable steps
  expect(legend(hot, 'heat').map((l) => l.key)).toEqual(['NONE', 'H1', 'H2', 'H3', 'H4', 'H5'])
  expect(legend({ ...scene } as Scene, 'heat').map((l) => l.key)).toEqual(['NONE']) // nothing picked yet
})
