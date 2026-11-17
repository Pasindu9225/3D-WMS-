import { useMemo } from 'react'
import { rackPolygon } from '@/features/layout/geometry'
import { t } from '@/lib/i18n'
import type { Scene } from './api'
import { ZONE_COLOR } from './colors'
import { routePoints, type PickRoute } from './route'

/**
 * Lite mode: the warehouse as a flat SVG plan for weak PCs. No WebGL and no three.js download; racks take the
 * colour of their bins (the worst status wins), a click opens the rack's bins.
 */
export function LiteMap({ scene, colors, selectedBin, selectedRack, onRack, route }: {
  scene: Scene
  /** A pick walk to draw over the plan (v1.2), if any. */
  route?: PickRoute | null
  /** Per-bin colours (same as the 3D view), so both modes tell the same story. */
  colors: string[]
  selectedBin: number | null
  selectedRack: number | null
  onRack: (rack: number) => void
}) {
  const view = useMemo(() => {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (let i = 0; i < scene.floor.length; i += 2) {
      minX = Math.min(minX, scene.floor[i]!); maxX = Math.max(maxX, scene.floor[i]!)
      minY = Math.min(minY, scene.floor[i + 1]!); maxY = Math.max(maxY, scene.floor[i + 1]!)
    }
    const pad = Math.max(maxX - minX, maxY - minY) * 0.03
    return `${minX - pad} ${minY - pad} ${maxX - minX + 2 * pad} ${maxY - minY + 2 * pad}`
  }, [scene.floor])
  // A rack's colour: its rarest bin colour, so one blocked bin among empty ones still shows on the plan.
  const rackColor = useMemo(() => {
    const out: string[] = scene.racks.map(() => '#94a3b8')
    const counts = scene.racks.map(() => new Map<string, number>())
    scene.bins.rack.forEach((r, i) => counts[r]!.set(colors[i]!, (counts[r]!.get(colors[i]!) ?? 0) + 1))
    counts.forEach((m, r) => {
      const ranked = [...m.entries()].sort((a, b) => a[1] - b[1]) // rarest first: the stand-out colour
      if (ranked.length) out[r] = ranked[0]![0]
    })
    return out
  }, [scene, colors])
  const binRack = selectedBin !== null ? scene.bins.rack[selectedBin] : undefined
  const pts = (flat: number[]) => flat.reduce((s, v, i) => s + (i % 2 ? `,${v} ` : `${v}`), '')

  return (
    <svg viewBox={view} className="h-full w-full bg-slate-900" role="img" aria-label={t('view3d.lite.map')}>
      <polygon points={pts(scene.floor)} fill="#cbd5e1" />
      {scene.zones.map((z) => (
        <polygon key={z.code} points={pts(z.polygon)} fill={ZONE_COLOR[z.type]} fillOpacity={0.25}
          stroke={ZONE_COLOR[z.type]} strokeWidth={60} strokeDasharray="300 200" />
      ))}
      {scene.openings.map((o, i) => <circle key={i} cx={o.x} cy={o.y} r={Math.max(400, o.widthMm / 2)} fill="#facc15" />)}
      {scene.racks.map((r, i) => {
        const poly = rackPolygon(r, r.widthMm, r.depthMm)
        const active = i === selectedRack || i === binRack
        return (
          <polygon key={r.code} points={poly.map((p) => `${p.x},${p.y}`).join(' ')} fill={rackColor[i]}
            stroke={active ? '#facc15' : '#1e293b'} strokeWidth={active ? 160 : 40} className="cursor-pointer"
            onClick={() => onRack(i)}>
            <title>{r.code}</title>
          </polygon>
        )
      })}
      {route && route.stops.length > 0 && <RouteOverlay route={route} />}
    </svg>
  )
}

/** The bins of one rack as a grid: top level first, slots left to right. A click picks a bin. */
export function RackBins({ scene, rack, colors, selectedBin, onBin, onClose }: {
  scene: Scene
  rack: number
  colors: string[]
  selectedBin: number | null
  onBin: (bin: number) => void
  onClose: () => void
}) {
  const r = scene.racks[rack]!
  const grid = useMemo(() => {
    const g: (number | null)[][] = r.levels.map(() => Array.from({ length: r.binsPerLevel }, () => null))
    scene.bins.rack.forEach((rk, i) => {
      if (rk === rack) g[scene.bins.level[i]!]![scene.bins.pos[i]!] = i
    })
    return g.reverse() // top level on top
  }, [scene, rack, r])
  return (
    <section aria-label={t('view3d.lite.rack', { code: r.code })}
      className="absolute right-3 bottom-3 max-h-[55%] max-w-[calc(100%-1.5rem)] overflow-auto rounded-lg border bg-card/95 p-3 shadow-lg">
      <div className="mb-2 flex items-center justify-between gap-4">
        <h2 className="font-semibold">{t('view3d.lite.rack', { code: r.code })}</h2>
        <button type="button" className="text-sm underline" onClick={onClose}>{t('view3d.bin.close')}</button>
      </div>
      <table className="border-separate border-spacing-1">
        <tbody>
          {grid.map((row, li) => (
            <tr key={li}>
              <th className="pr-1 text-right text-xs font-normal text-muted-foreground">{t('view3d.lite.level', { n: grid.length - li })}</th>
              {row.map((bin, pi) => (
                <td key={pi}>
                  {bin !== null && (
                    <button type="button" aria-label={scene.bins.codes[bin]} title={scene.bins.codes[bin]}
                      onClick={() => onBin(bin)} style={{ background: colors[bin] }}
                      className={`size-7 rounded border ${bin === selectedBin ? 'border-2 border-yellow-400' : 'border-slate-700'}`} />
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

/** The pick walk on the plan: a line from the start through every stop and back, numbered stops. */
function RouteOverlay({ route }: { route: PickRoute }) {
  const pts = routePoints(route)
  return (
    <g aria-label={t(route.stops.length === 1 ? 'route.labelOne' : 'route.label', { n: route.stops.length, m: Math.round(route.metres) })} role="img">
      <polyline points={pts.map((p) => `${p.x},${p.y}`).join(' ')} fill="none" stroke="#22d3ee" strokeWidth={180}
        strokeLinejoin="round" strokeDasharray="600 300" />
      <circle cx={route.startX} cy={route.startY} r={700} fill="#22d3ee" />
      {route.stops.map((s) => (
        <g key={s.step}>
          <circle cx={s.x} cy={s.y} r={900} fill="#0e7490" stroke="#ffffff" strokeWidth={120} />
          <text x={s.x} y={s.y} fill="#ffffff" fontSize={1000} fontWeight={700} textAnchor="middle" dominantBaseline="central">{s.step}</text>
        </g>
      ))}
    </g>
  )
}
