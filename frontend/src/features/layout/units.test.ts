import { expect, it } from 'vitest'
import { format, formatWith, parse } from './units'

it('converts typed lengths in any unit to millimetres', () => {
  expect(parse('400', 'ft')).toBe(121_920)
  expect(parse('400ft', 'm')).toBe(121_920)
  expect(parse('12.5 m', 'ft')).toBe(12_500)
  expect(parse('30cm', 'm')).toBe(300)
  expect(parse("10'", 'm')).toBe(3048)
  expect(parse('6 in', 'm')).toBe(152)
  expect(parse('abc', 'm')).toBeNull()
  expect(parse('5 parsecs', 'm')).toBeNull()
})

it('shows lengths in the chosen unit', () => {
  expect(format(121_920, 'ft')).toBe('400')
  expect(formatWith(12_500, 'm')).toBe('12.5 m')
  expect(format(15_240, 'ft')).toBe('50')
})
