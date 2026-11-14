import { expect, it } from 'vitest'
import { findBin, suggestions } from './search'

const codes = ['Z1-R01-01-10', 'Z1-R01-01-100', 'Z1-R02-01-01', 'Z2-R01-01-01']

it('finds the exact code first, in any case, even when it is a prefix of another code', () => {
  expect(findBin(codes, 'z1-r01-01-10')).toBe(0)
  expect(findBin(codes, ' Z1-R01-01-100 ')).toBe(1)
})

it('falls back to the first code starting with the text, or nothing', () => {
  expect(findBin(codes, 'Z1-R02')).toBe(2)
  expect(findBin(codes, 'Z9')).toBeNull()
  expect(findBin(codes, '  ')).toBeNull()
})

it('suggests matching codes once two characters are typed', () => {
  expect(suggestions(codes, 'z')).toEqual([])
  expect(suggestions(codes, 'z1-r01')).toEqual(['Z1-R01-01-10', 'Z1-R01-01-100'])
  expect(suggestions(codes, 'Z', 1)).toEqual([])
  expect(suggestions(codes, 'Z1', 1)).toEqual(['Z1-R01-01-10'])
})
