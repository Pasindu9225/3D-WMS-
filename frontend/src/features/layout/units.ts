/** Display units. Everything is stored as whole millimetres; units only change what people type and see. */
export const UNITS = { mm: 1, cm: 10, m: 1000, in: 25.4, ft: 304.8 } as const
export type Unit = keyof typeof UNITS

const ALIASES: Record<string, Unit> = {
  mm: 'mm', cm: 'cm', m: 'm', metre: 'm', metres: 'm', meter: 'm', meters: 'm',
  in: 'in', inch: 'in', inches: 'in', '"': 'in', ft: 'ft', foot: 'ft', feet: 'ft', "'": 'ft',
}

/** Length for display: up to 2 decimals in the chosen unit, no trailing zeros. */
export function format(mm: number, unit: Unit): string {
  return String(Math.round((mm / UNITS[unit]) * 100) / 100)
}

/** Same, with the unit name ("12.5 m", "400 ft"). */
export function formatWith(mm: number, unit: Unit): string {
  return `${format(mm, unit)} ${unit}`
}

/**
 * Typed length to mm. A plain number is in the current unit; a suffix converts ("400ft", "12.5 m",
 * "30 cm", "10'"). Returns null when it is not a length.
 */
export function parse(text: string, unit: Unit): number | null {
  const m = /^\s*(\d+(?:\.\d+)?|\.\d+)\s*([a-z"']*)\s*$/i.exec(text)
  if (!m) return null
  const u = m[2] ? ALIASES[m[2].toLowerCase()] : unit
  if (!u) return null
  return Math.round(Number(m[1]) * UNITS[u])
}

/** Area for display in square units ("1453.24" for sq ft); input in mm². */
export function formatArea(mm2: number, unit: Unit): string {
  return String(Math.round((mm2 / UNITS[unit] / UNITS[unit]) * 100) / 100)
}

/** Weights are stored in grams; typed in kg by default, or with a unit ("2t", "4400 lb", "500kg"). */
export const WEIGHTS = { g: 1, kg: 1000, t: 1_000_000, lb: 453.59237 } as const
const WEIGHT_ALIASES: Record<string, keyof typeof WEIGHTS> = {
  g: 'g', kg: 'kg', kgs: 'kg', t: 't', ton: 't', tons: 't', tonne: 't', tonnes: 't', lb: 'lb', lbs: 'lb',
}

export function parseWeight(text: string): number | null {
  const m = /^\s*(\d+(?:\.\d+)?|\.\d+)\s*([a-z]*)\s*$/i.exec(text)
  if (!m) return null
  const u = m[2] ? WEIGHT_ALIASES[m[2].toLowerCase()] : 'kg'
  if (!u) return null
  return Math.round(Number(m[1]) * WEIGHTS[u])
}

/** "1,000 kg" style, from grams. */
export function formatKg(g: number): string {
  return `${(Math.round(g / 10) / 100).toLocaleString('en')} kg`
}

/**
 * Volume in cm³ from what people know: litres ("1.5") or a box in cm ("30x20x15", also "30 × 20 × 15").
 * Null when it is neither.
 */
/** A carton size "40x30x25" (cm, optional "cm") into millimetre sides [length, width, height]; null if unreadable. */
export function parseCarton(text: string): [number, number, number] | null {
  const m = /^(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)\s*(cm)?$/.exec(text.trim().toLowerCase())
  if (!m) return null
  const sides = [m[1], m[2], m[3]].map((v) => Math.round(Number(v) * 10)) as [number, number, number]
  return sides.every((s) => s >= 10 && s <= 5000) ? sides : null
}

export function parseVolume(text: string): number | null {
  const t = text.trim().toLowerCase()
  const box = /^(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)\s*(cm)?$/.exec(t)
  if (box) return Math.round(Number(box[1]) * Number(box[2]) * Number(box[3]))
  const litres = /^(\d+(?:\.\d+)?|\.\d+)\s*(l|litres?|liters?)?$/.exec(t)
  return litres ? Math.round(Number(litres[1]) * 1000) : null
}

/** Money in cents from "450", "450.50" or "1,250.00"; null when it is not an amount. */
/** Cents to "1,250.50". */
export function formatMoney(minor: number): string {
  return (minor / 100).toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function parseMoney(text: string): number | null {
  const t = text.trim().replace(/,/g, '')
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null
  return Math.round(Number(t) * 100)
}
