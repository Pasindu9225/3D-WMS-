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
