import { expect, it } from 'vitest'
import { format, formatArea, formatWith, parse } from './units'

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

it('shows areas in square units', () => {
  expect(formatArea(15_000 * 9_000, 'ft')).toBe('1453.13')
  expect(formatArea(15_000 * 9_000, 'm')).toBe('135')
})

it('converts typed weights to grams', async () => {
  const { parseWeight, formatKg } = await import('./units')
  expect(parseWeight('1000')).toBe(1_000_000)
  expect(parseWeight('2t')).toBe(2_000_000)
  expect(parseWeight('4400 lb')).toBe(1_995_806)
  expect(parseWeight('heavy')).toBeNull()
  expect(formatKg(1_500_000)).toBe('1,500 kg')
})

it('reads volumes in litres or as a box in cm, and money in cents', async () => {
  const { parseVolume, parseMoney } = await import('./units')
  expect(parseVolume('1.1')).toBe(1100)
  expect(parseVolume('2 L')).toBe(2000)
  expect(parseVolume('30x20x15')).toBe(9000)
  expect(parseVolume('30 × 20 × 15 cm')).toBe(9000)
  expect(parseVolume('big')).toBeNull()
  expect(parseMoney('1,250.50')).toBe(125050)
  expect(parseMoney('450')).toBe(45000)
  expect(parseMoney('4.555')).toBeNull()
})

it('reads a carton size in cm and keeps the three sides (v2.1)', async () => {
  const { parseCarton } = await import('./units')
  expect(parseCarton('40x30x25')).toEqual([400, 300, 250])
  expect(parseCarton('40 × 30.5 × 25 cm')).toEqual([400, 305, 250])
  expect(parseCarton('40x30')).toBeNull()
  expect(parseCarton('0.5x30x25')).toBeNull() // 5 mm: smaller than any real carton
})
