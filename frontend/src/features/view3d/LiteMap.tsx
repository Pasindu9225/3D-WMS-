import { useMemo } from 'react'
import { nearestOnOutline, pointInPolygon, rackPolygon } from '@/features/layout/geometry'
import type { Opening } from '@/features/warehouses/api'
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
    return { box: `${minX - pad} ${minY - pad} ${maxX - minX + 2 * pad} ${maxY - minY + 2 * pad}`, size: Math.max(maxX - minX, maxY - minY) }
  }, [scene.floor])
  const outline = useMemo(() => Array.from({ length: scene.floor.length / 2 }, (_, i) => ({ x: scene.floor[i * 2]!, y: scene.floor[i * 2 + 1]! })), [scene.floor])
  const fs = view.size * 0.012 // label text height, in floor mm (about 14 px on a typical screen)
  const labels = useMemo(() => placeLabels(scene, outline, fs), [scene, outline, fs])
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
    <svg viewBox={view.box} className="h-full w-full bg-slate-900" role="img" aria-label={t('view3d.lite.map')}>
      <defs>
        <pattern id="hazard" width={600} height={600} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width={600} height={600} fill="#facc15" />
          <rect width={300} height={600} fill="#111827" />
        </pattern>
      </defs>
      <polygon points={pts(scene.floor)} fill="#cbd5e1" stroke="#475569" strokeWidth={fs * 0.35} />
      {scene.zones.map((z) => (
        <polygon key={z.code} points={pts(z.polygon)} fill={ZONE_COLOR[z.type]} fillOpacity={0.25}
          stroke={ZONE_COLOR[z.type]} strokeWidth={60} strokeDasharray="300 200" />
      ))}
      {scene.racks.map((r, i) => {
        const poly = rackPolygon(r, r.widthMm, r.depthMm)
        const active = i === selectedRack || i === binRack
        return (
          <polygon key={r.code} points={poly.map((p) => `${p.x},${p.y}`).join(' ')} fill={rackColor[i]}
            stroke={active ? '#facc15' : '#1e293b'} strokeWidth={active ? 160 : 40} className="cursor-pointer"
            strokeDasharray={r.style === 'FLOOR' ? '300 200' : undefined} fillOpacity={r.style === 'FLOOR' ? 0.6 : 1}
            onClick={() => onRack(i)}>
            <title>{r.code}</title>
          </polygon>
        )
      })}
      {scene.racks.map((r) => {
        // the rack number written along the rack, as big as its depth allows
        const size = Math.min(r.depthMm * 0.62, r.widthMm / (r.code.length * 0.62))
        if (size < view.size * 0.004) return null // too small to read at this size
        return (
          <text key={'t' + r.code} x={r.x} y={r.y} transform={`rotate(${r.rotation} ${r.x} ${r.y})`} fontSize={size}
            fontWeight={600} fill="#0f172a" textAnchor="middle" dominantBaseline="central" pointerEvents="none">{r.code}</text>
        )
      })}
      {scene.openings.map((o, i) => <OpeningSymbol key={i} o={o} outline={outline} fs={fs} />)}
      {labels.zones.map((z) => <ZoneTag key={'zt' + z.code} {...z} fs={fs} />)}
      {labels.openings.map((l, i) => <OpeningLabel key={'ol' + i} {...l} fs={fs} />)}
      {route && route.stops.length > 0 && <RouteOverlay route={route} />}
    </svg>
  )
}

type Box = { x: number; y: number; w: number; h: number } // top-left corner and size, floor mm

const overlaps = (a: Box, b: Box) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

/**
 * Where the names go, so none covers another: each zone's tag just above its area (inside it when there is no room
 * above), then each door and dock's tag indoors in front of it, stepped further in while it would cover a tag
 * already placed.
 */
function placeLabels(scene: Scene, outline: { x: number; y: number }[], fs: number) {
  const placed: Box[] = []
  const floorTop = Math.min(...outline.map((p) => p.y))
  const zones = scene.zones.map((z) => {
    let minX = Infinity, minY = Infinity
    for (let i = 0; i < z.polygon.length; i += 2) {
      minX = Math.min(minX, z.polygon[i]!)
      minY = Math.min(minY, z.polygon[i + 1]!)
    }
    const h = fs * 1.7
    const w = h + fs * 0.6 + z.name.length * fs * 0.56 + fs * 0.8
    const above = minY - h - fs * 0.3
    const box = { x: minX, y: above > floorTop ? above : minY + fs * 0.5, w, h }
    placed.push(box)
    return { code: z.code, name: z.name, type: z.type, ...box }
  })
  const openings = scene.openings.map((o) => {
    const { n, w: width } = wallSide(o, outline)
    const dock = o.kind === 'DOCK'
    const h = fs * 1.4
    const w = o.label.length * fs * 0.5 + fs * 1.4
    const halfN = (Math.abs(n.x) * w + Math.abs(n.y) * h) / 2 // half the tag's size across the wall
    let d = (dock ? Math.min(3000, Math.max(1500, width)) : width) + fs * 0.5 + halfN
    let box: Box = { x: 0, y: 0, w, h }
    for (let step = 0; step < 6; step++) {
      box = { x: o.x + n.x * d - w / 2, y: o.y + n.y * d - h / 2, w, h }
      if (!placed.some((p) => overlaps(p, box))) break
      d += 2 * halfN + fs * 0.3
    }
    placed.push(box)
    return { text: o.label, color: dock ? '#f59e0b' : '#2563eb', ...box }
  })
  return { zones, openings }
}

/** The wall an opening is on: its angle (degrees), the unit vector pointing indoors, and the opening's width. */
function wallSide(o: Opening, outline: { x: number; y: number }[]) {
  const wall = nearestOnOutline(outline, o)
  const a = (wall.angle * Math.PI) / 180
  const inside = pointInPolygon({ x: o.x - Math.sin(a) * 1000, y: o.y + Math.cos(a) * 1000 }, outline)
  const s = inside ? 1 : -1
  return { angle: wall.angle, inward: s, n: { x: -Math.sin(a) * s, y: Math.cos(a) * s }, w: o.widthMm }
}

/** A zone's name tag: a badge in the zone's colour with its code, then its name. */
function ZoneTag({ code, name, type, x, y, w, h, fs }: Box & { code: string; name: string; type: Scene['zones'][number]['type']; fs: number }) {
  return (
    <g pointerEvents="none">
      <rect x={x} y={y} width={w} height={h} rx={h / 2} fill="#0f172a" fillOpacity={0.8} />
      <rect x={x + h * 0.12} y={y + h * 0.12} width={h * 0.76} height={h * 0.76} rx={h * 0.22} fill={ZONE_COLOR[type]} />
      <text x={x + h * 0.5} y={y + h / 2} fontSize={fs * 0.85} fontWeight={700} fill="#ffffff" textAnchor="middle" dominantBaseline="central">
        {code.slice(0, 3)}
      </text>
      <text x={x + h + fs * 0.4} y={y + h / 2} fontSize={fs} fontWeight={600} fill="#f8fafc" dominantBaseline="central">{name}</text>
    </g>
  )
}

/**
 * A door or dock drawn like on an architect's plan, at its real width: a gap in the wall; a door adds its leaf and
 * swing arc on the indoor side, a dock its shutter and a striped loading strip.
 */
function OpeningSymbol({ o, outline, fs }: { o: Opening; outline: { x: number; y: number }[]; fs: number }) {
  const { angle, inward } = wallSide(o, outline)
  const w = o.widthMm
  const t = Math.max(250, fs * 0.35) // wall line thickness
  const dock = o.kind === 'DOCK'
  const color = dock ? '#f59e0b' : '#2563eb'
  const strip = Math.min(3000, Math.max(1500, w))
  return (
    <g transform={`translate(${o.x} ${o.y}) rotate(${angle}) scale(1 ${inward})`} pointerEvents="none">
      <title>{o.label}</title>
      <rect x={-w / 2} y={-t / 2} width={w} height={t} fill="#ffffff" stroke={color} strokeWidth={t * 0.3} />
      {dock ? (
        <>
          <rect x={-w / 2} y={t / 2} width={w} height={strip} fill="url(#hazard)" opacity={0.85} />
          {[0.25, 0.5, 0.75].map((f) => <line key={f} x1={-w / 2} x2={w / 2} y1={-t / 2 + t * f} y2={-t / 2 + t * f} stroke={color} strokeWidth={t * 0.12} />)}
        </>
      ) : (
        <>
          <line x1={-w / 2} y1={0} x2={-w / 2} y2={w} stroke={color} strokeWidth={Math.max(60, fs * 0.12)} />
          <path d={`M ${-w / 2} ${w} A ${w} ${w} 0 0 0 ${w / 2} 0`} fill="none" stroke={color} strokeWidth={Math.max(40, fs * 0.08)}
            strokeDasharray={`${fs * 0.3} ${fs * 0.2}`} />
        </>
      )}
    </g>
  )
}

/** A door or dock's name: a slim pill with a bar in its colour (blue door, orange dock). */
function OpeningLabel({ text, color, x, y, w, h, fs }: Box & { text: string; color: string; fs: number }) {
  return (
    <g pointerEvents="none">
      <rect x={x} y={y} width={w} height={h} rx={h / 2} fill="#0f172a" fillOpacity={0.8} />
      <rect x={x + fs * 0.35} y={y + h * 0.22} width={fs * 0.22} height={h * 0.56} rx={fs * 0.11} fill={color} />
      <text x={x + w / 2 + fs * 0.25} y={y + h / 2} fontSize={fs * 0.85} fontWeight={600} fill="#f8fafc" textAnchor="middle"
        dominantBaseline="central">{text}</text>
    </g>
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
