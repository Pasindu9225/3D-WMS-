import { expect, it } from 'vitest'
import type { Scene } from './api'
import { applyPush } from './live'

const scene = { bins: { ids: ['a', 'b', 'c'] }, flags: '000' } as unknown as Scene

it('changes only the pushed bin, and ignores bins not in this scene or layout pushes', () => {
  expect(applyPush(scene, { type: 'BIN', id: 'b', flags: 3 }).flags).toBe('030')
  expect(applyPush(scene, { type: 'BIN', id: 'zz', flags: 1 })).toBe(scene)
  expect(applyPush(scene, { type: 'LAYOUT', publishedAt: 'x' })).toBe(scene)
})
