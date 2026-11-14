import { expect, it } from 'vitest'
import { slowFromFrameTimes } from './perf'

it('judges only with enough movement, then by the median frame rate', () => {
  expect(slowFromFrameTimes(Array(59).fill(0.03))).toBe(false) // not enough yet
  expect(slowFromFrameTimes(Array(60).fill(0.07))).toBe(true) // ~14 fps
  expect(slowFromFrameTimes(Array(60).fill(0.03))).toBe(false) // ~33 fps
  expect(slowFromFrameTimes(Array(9).fill(0.6))).toBe(false) // too few frames even if slow
  expect(slowFromFrameTimes(Array(10).fill(0.6))).toBe(true) // 6 s of 1.7 fps: decide early
})
