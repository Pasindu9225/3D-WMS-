import { expect, it } from 'vitest'
import type { Scene } from './api'
import { binColors, legend, STATUS_COLOR, statusOf, ZONE_COLOR } from './colors'

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
