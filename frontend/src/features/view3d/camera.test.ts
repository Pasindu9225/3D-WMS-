import { expect, it, vi } from 'vitest'
import { shortcut, type CameraApi } from './camera'

it('maps keys to camera moves, Shift pans three times further', () => {
  const api: CameraApi = { reset: vi.fn(), top: vi.fn(), zoom: vi.fn(), pan: vi.fn(), flyTo: vi.fn() }
  shortcut('t', false, api, 5)?.()
  shortcut('Home', false, api, 5)?.()
  shortcut('+', false, api, 5)?.()
  shortcut('-', false, api, 5)?.()
  shortcut('ArrowLeft', false, api, 5)?.()
  shortcut('ArrowUp', true, api, 5)?.()
  expect(api.top).toHaveBeenCalled()
  expect(api.reset).toHaveBeenCalled()
  expect(api.zoom).toHaveBeenNthCalledWith(1, 1)
  expect(api.zoom).toHaveBeenNthCalledWith(2, -1)
  expect(api.pan).toHaveBeenNthCalledWith(1, -5, 0)
  expect(api.pan).toHaveBeenNthCalledWith(2, 0, 15)
  expect(shortcut('x', false, api, 5)).toBeNull()
})

it('stands in the aisle in front of a bin, out from its rack face', async () => {
  const { viewpoint } = await import('./camera')
  const straight = viewpoint({ x: 10, y: 1, z: 5, w: 0.3, h: 1, d: 1, rotY: 0 })
  expect(straight).toEqual({ x: 10.8, y: 1.6, z: 5 + 0.5 + 1.8 })
  const turned = viewpoint({ x: 10, y: 1, z: 5, w: 0.3, h: 1, d: 1, rotY: -Math.PI / 2 }) // rack turned 90° on the plan
  expect(turned.x).toBeCloseTo(10 - 2.3)
  expect(turned.z).toBeCloseTo(5 + 0.8)
})
