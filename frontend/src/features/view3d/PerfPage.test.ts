import { expect, it } from 'vitest'
import { fakeScene } from './PerfPage'

it('builds a fake warehouse with exactly the requested bins, in racks of 50', () => {
  const s = fakeScene(10_000)
  expect(s.bins.ids).toHaveLength(10_000)
  expect(s.racks).toHaveLength(200)
  expect(s.flags).toHaveLength(10_000)
  expect(new Set(s.bins.codes).size).toBe(10_000)
  expect(fakeScene(75).racks).toHaveLength(2)
})
