import type Konva from 'konva'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Circle, Group, Layer, Line, Rect, Stage, Text } from 'react-konva'
import type { RackType, ZoneType } from '@/features/warehouses/api'
import { useEditor } from './editorStore'
import { bounds, nearestOnOutline, rackRect } from './geometry'

/** Zone fills: soft, distinct, readable in light and dark mode (semi-transparent over the floor). */
const ZONE_FILL: Record<ZoneType, string> = {
  RECEIVING: 'rgba(59,130,246,0.18)',
  STORAGE: 'rgba(100,116,139,0.18)',
  PICKING: 'rgba(16,185,129,0.18)',
  DISPATCH: 'rgba(245,158,11,0.20)',
  QUARANTINE: 'rgba(239,68,68,0.18)',
  COLD: 'rgba(14,165,233,0.22)',
}

interface View {
  scale: number // screen px per mm
  x: number
  y: number
}

/**
 * The floor plan. World units are millimetres; the Konva stage scale turns them into pixels, so
 * zooming never rounds coordinates. Strokes and handles keep a constant on-screen size.
 */
export function FloorCanvas({ templates, editable, fitSignal }: {
  templates: Record<string, RackType>
  editable: boolean
  fitSignal: number
}) {
  const doc = useEditor((s) => s.doc)
  const selection = useEditor((s) => s.selection)
  const { moveFloorPoint, insertFloorPoint, removeFloorPoint, moveOpening, select } = useEditor.getState()
  const box = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState<{ width: number; height: number } | null>(null)
  const [view, setView] = useState<View>({ scale: 0.02, x: 40, y: 40 })

  useEffect(() => {
    const el = box.current
    if (!el) return
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setSize({ width: entry.contentRect.width, height: entry.contentRect.height })
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  // Fit the floor into the view on first show and on each "Fit" press (adjusting state during render).
  const [fitted, setFitted] = useState<number | null>(null)
  if (doc && size && fitted !== fitSignal) {
    setFitted(fitSignal)
    setView(fitView(doc.floor, size))
  }

  const zoom = (e: Konva.KonvaEventObject<WheelEvent>) => {
    e.evt.preventDefault()
    const stage = e.target.getStage()
    const pointer = stage?.getPointerPosition()
    if (!pointer) return
    const factor = e.evt.deltaY > 0 ? 1 / 1.15 : 1.15
    const scale = Math.min(1, Math.max(0.001, view.scale * factor))
    const worldX = (pointer.x - view.x) / view.scale
    const worldY = (pointer.y - view.y) / view.scale
    setView({ scale, x: pointer.x - worldX * scale, y: pointer.y - worldY * scale }) // zoom around the pointer
  }

  const grid = useMemo(() => (size ? gridLines(view, size) : []), [view, size])
  if (!doc || !size) return <div ref={box} className="h-full w-full" />
  const px = 1 / view.scale // one screen pixel in mm
  const floorFlat = doc.floor.flatMap((p) => [p.x, p.y])

  return (
    <div ref={box} className="h-full w-full overflow-hidden bg-muted/40" data-testid="floor-canvas">
      <Stage
        width={size.width}
        height={size.height}
        scaleX={view.scale}
        scaleY={view.scale}
        x={view.x}
        y={view.y}
        draggable
        onWheel={zoom}
        onDragEnd={(e) => e.target === e.target.getStage() && setView((v) => ({ ...v, x: e.target.x(), y: e.target.y() }))}
        onMouseDown={(e) => e.target === e.target.getStage() && select(null)}
      >
        <Layer listening={false}>
          {grid.map((g) => (
            <Line key={g.key} points={g.points} stroke={g.major ? '#94a3b8' : '#cbd5e1'} strokeWidth={g.major ? 1 : 0.5}
              opacity={g.major ? 0.7 : 0.45} strokeScaleEnabled={false} />
          ))}
        </Layer>
        <Layer>
          <Line points={floorFlat} closed fill="rgba(255,255,255,0.65)" stroke="#334155" strokeWidth={2}
            strokeScaleEnabled={false} hitStrokeWidth={12}
            onDblClick={(e) => {
              if (!editable) return
              const p = e.target.getStage()?.getRelativePointerPosition()
              if (p) insertFloorPoint(p)
            }} />
          {doc.zones.map((z) => (
            <Group key={z.code} listening={false}>
              <Line points={z.polygon.flatMap((p) => [p.x, p.y])} closed fill={ZONE_FILL[z.type]} stroke="#64748b"
                strokeWidth={1} dash={[6, 4]} strokeScaleEnabled={false} />
              <Text x={bounds(z.polygon).minX + 8 * px} y={bounds(z.polygon).minY + 6 * px} text={`${z.code} · ${z.name}`}
                fontSize={12 * px} fill="#475569" />
            </Group>
          ))}
          {doc.racks.map((r) => {
            const t = templates[r.templateId]
            if (!t) return null
            const rect = rackRect(r, t.widthMm, t.depthMm)
            return (
              <Group key={r.code} listening={false}>
                <Rect {...rect} fill="#e2e8f0" stroke="#475569" strokeWidth={1} strokeScaleEnabled={false} />
                <Text x={rect.x} y={rect.y + rect.height / 2 - 5 * px} width={rect.width} align="center" text={r.code}
                  fontSize={10 * px} fill="#1e293b" />
              </Group>
            )
          })}
          {doc.openings.map((o, i) => {
            const wall = nearestOnOutline(doc.floor, o)
            const selected = selection?.kind === 'opening' && selection.index === i
            return (
              <Group key={i} x={o.x} y={o.y} rotation={wall.angle} draggable={editable}
                onMouseDown={() => select({ kind: 'opening', index: i })}
                onDragMove={(e) => moveOpening(i, { x: e.target.x(), y: e.target.y() })}
                onDragEnd={(e) => {
                  const placed = useEditor.getState().doc?.openings[i]
                  if (placed) e.target.position({ x: placed.x, y: placed.y })
                }}>
                <Rect x={-o.widthMm / 2} y={-300} width={o.widthMm} height={600}
                  fill={o.kind === 'DOCK' ? '#f59e0b' : '#3b82f6'} opacity={0.85}
                  stroke={selected ? '#0f172a' : undefined} strokeWidth={selected ? 2 : 0} strokeScaleEnabled={false} />
              </Group>
            )
          })}
          {doc.openings.map((o, i) => {
            // Label upright and on one line, nudged from the wall towards the middle of the floor.
            const c = centroid(doc.floor)
            const len = Math.hypot(c.x - o.x, c.y - o.y) || 1
            return (
              <Text key={'label' + i} x={o.x + ((c.x - o.x) / len) * 700} y={o.y + ((c.y - o.y) / len) * 700}
                text={o.label} fontSize={11 * px} fill="#0f172a" wrap="none" listening={false}
                offsetX={labelOffset(o.label, 11 * px, (c.x - o.x) / len)} offsetY={6 * px} />
            )
          })}
          {editable && doc.floor.map((p, i) => (
            <Circle key={i} x={p.x} y={p.y} radius={6 * px} fill="#fff" stroke="#2563eb" strokeWidth={2}
              strokeScaleEnabled={false} draggable
              onDragMove={(e) => moveFloorPoint(i, { x: e.target.x(), y: e.target.y() })}
              onDragEnd={(e) => e.target.position(useEditor.getState().doc?.floor[i] ?? p)}
              onContextMenu={(e) => {
                e.evt.preventDefault()
                removeFloorPoint(i)
              }} />
          ))}
        </Layer>
      </Stage>
    </div>
  )
}

/**
 * Horizontal anchor for a wall label, as an offset into the text: starts at a left wall (dirX = 1),
 * ends at a right wall (dirX = -1), centred on top and bottom walls; blends in between.
 */
function labelOffset(label: string, fontSize: number, dirX: number): number {
  const width = label.length * 0.55 * fontSize // Konva's default sans: about 0.55 em per character
  return (width * (1 - dirX)) / 2
}

function centroid(points: { x: number; y: number }[]) {
  const sum = points.reduce((a, p) => ({ x: a.x + p.x, y: a.y + p.y }), { x: 0, y: 0 })
  return { x: sum.x / points.length, y: sum.y / points.length }
}

function fitView(floor: { x: number; y: number }[], size: { width: number; height: number }): View {
  const b = bounds(floor)
  const scale = Math.min(size.width / (b.maxX - b.minX || 1), size.height / (b.maxY - b.minY || 1)) * 0.9
  return {
    scale,
    x: (size.width - (b.maxX - b.minX) * scale) / 2 - b.minX * scale,
    y: (size.height - (b.maxY - b.minY) * scale) / 2 - b.minY * scale,
  }
}

/** Grid lines covering the visible area: every 1 m, bold every 5 m; only 5 m lines when zoomed far out. */
function gridLines(view: View, size: { width: number; height: number }) {
  const minor = view.scale * 1000 < 6 ? 5000 : 1000
  const x0 = Math.floor(-view.x / view.scale / minor) * minor
  const y0 = Math.floor(-view.y / view.scale / minor) * minor
  const x1 = (size.width - view.x) / view.scale
  const y1 = (size.height - view.y) / view.scale
  const lines: { key: string; points: number[]; major: boolean }[] = []
  for (let x = x0; x <= x1 && lines.length < 600; x += minor) {
    lines.push({ key: `x${x}`, points: [x, y0, x, y1], major: x % 5000 === 0 })
  }
  for (let y = y0; y <= y1 && lines.length < 1200; y += minor) {
    lines.push({ key: `y${y}`, points: [x0, y, x1, y], major: y % 5000 === 0 })
  }
  return lines
}
