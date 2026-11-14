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
