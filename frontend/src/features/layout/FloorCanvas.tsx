import type Konva from 'konva'
import { memo, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { Circle, Group, Label, Layer, Line, Rect, Shape, Stage, Tag, Text, Transformer } from 'react-konva'
import type { Opening, Rack, RackType, ZoneType } from '@/features/warehouses/api'
import { useEditor, type Placing } from './editorStore'
import { bounds, nearestOnOutline, pointInPolygon, rackSize, snap, type RackStatus } from './geometry'
import { formatWith, UNITS, type Unit } from './units'

/** Zone fills: soft, distinct, readable in light and dark mode (semi-transparent over the floor). */
const ZONE_FILL: Record<ZoneType, string> = {
  RECEIVING: 'rgba(59,130,246,0.18)',
  STORAGE: 'rgba(100,116,139,0.18)',
  PICKING: 'rgba(16,185,129,0.18)',
  DISPATCH: 'rgba(245,158,11,0.20)',
  QUARANTINE: 'rgba(239,68,68,0.18)',
  COLD: 'rgba(14,165,233,0.22)',
}

/** Rack fill by live check: red = overlap or outside its zone, amber = aisle narrower than the minimum. */
const RACK_FILL: Record<RackStatus | 'ok', string> = { ok: '#e2e8f0', warn: '#fde68a', bad: '#fca5a5' }

interface View {
  scale: number // screen px per mm
  x: number
  y: number
}

/**
 * The floor plan. World units are millimetres; the Konva stage scale turns them into pixels, so
 * zooming never rounds coordinates. Strokes and handles keep a constant on-screen size.
 */
export function FloorCanvas({ templates, statuses, editable, fitSignal }: {
  templates: Record<string, RackType>
  /** Live check result per rack (same index as doc.racks). */
  statuses: (RackStatus | undefined)[]
  editable: boolean
  fitSignal: number
}) {
  const doc = useEditor((s) => s.doc)
  const selection = useEditor((s) => s.selection)
  const drawing = useEditor((s) => s.drawing)
  const cursor = useEditor((s) => s.cursor)
  const unit = useEditor((s) => s.unit)
  const placing = useEditor((s) => s.placing)
  const [ghost, setGhost] = useState<{ x: number; y: number } | null>(null)
  const { moveFloorPoint, insertFloorPoint, removeFloorPoint, moveOpening, select, addDrawPoint, finishDrawing,
    moveZonePoint, setCursor, moveZone, place, cancelPlacing } = useEditor.getState()
  const rackNodes = useRef(new Map<number, Konva.Group>())
  const zoneDrag = useRef<{ start: { x: number; y: number }; applied: { x: number; y: number } } | null>(null)
  const transformer = useRef<Konva.Transformer>(null)
  const [live, setLive] = useState<{ x: number; y: number; text: string } | null>(null)

  // Rotate and resize handles follow the selected rack.
  useEffect(() => {
    const node = selection?.kind === 'rack' ? rackNodes.current.get(selection.index) : undefined
    transformer.current?.nodes(node ? [node] : [])
  }, [selection, doc, drawing])
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
    <div ref={box} className={'relative h-full w-full overflow-hidden bg-muted/40' + (placing ? ' cursor-crosshair' : '')}
      data-testid="floor-canvas" onMouseLeave={() => setGhost(null)}>
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
        onMouseDown={(e) => !drawing && e.target === e.target.getStage() && select(null)}
        onClick={(e) => {
          if ((!drawing && !placing) || e.evt.button !== 0) return
          const p = e.target.getStage()?.getRelativePointerPosition()
          if (!p) return
          if (placing) place(p)
          else addDrawPoint(p)
        }}
        onContextMenu={(e) => {
          if (!placing) return
          e.evt.preventDefault() // right-click stops placing
          cancelPlacing()
          setGhost(null)
        }}
        onDblClick={() => drawing && finishDrawing()}
        onMouseMove={(e) => {
          if (!drawing && !placing) return
          const p = e.target.getStage()?.getRelativePointerPosition()
          if (!p) return
          if (placing) setGhost(p)
          else setCursor({ x: Math.round(p.x / 100) * 100, y: Math.round(p.y / 100) * 100 })
        }}
      >
        <Layer listening={false}>
          {/* One shape for the whole grid: hundreds of Line nodes made every zoom step slow on weak laptops. */}
          <Shape perfectDrawEnabled={false} sceneFunc={(ctx) => {
            for (const major of [false, true]) {
              ctx.beginPath()
              for (const g of grid) {
                if (g.major !== major) continue
                ctx.moveTo(g.points[0]!, g.points[1]!)
                ctx.lineTo(g.points[2]!, g.points[3]!)
              }
              ctx.setAttr('strokeStyle', major ? 'rgba(148,163,184,0.7)' : 'rgba(203,213,225,0.45)')
              ctx.setAttr('lineWidth', (major ? 1 : 0.5) / view.scale)
              ctx.stroke()
            }
          }} />
        </Layer>
        {/* While drawing or placing, clicks must reach the stage, not select doors or add corners to the old outline. */}
        <Layer listening={!drawing && !placing}>
          <Line points={floorFlat} closed fill="rgba(255,255,255,0.65)" stroke="#334155" strokeWidth={2}
            strokeScaleEnabled={false} hitStrokeWidth={12}
            onDblClick={(e) => {
              if (!editable) return
              const p = e.target.getStage()?.getRelativePointerPosition()
              if (p) insertFloorPoint(p)
            }} />
          {edgeLabels(doc.floor, unit, px, '#334155')}
          {doc.zones.map((z, i) => {
            const selected = selection?.kind === 'zone' && selection.index === i
            return (
              <Group key={i} draggable={editable} onMouseDown={() => editable && select({ kind: 'zone', index: i })}
                onDragStart={(e) => {
                  zoneDrag.current = { start: e.target.absolutePosition(), applied: { x: 0, y: 0 } }
                }}
                dragBoundFunc={(pos) => {
                  // Konva reports the total drag since it started. Apply only the part not applied yet, in whole
                  // 0.1 m steps; the group stays where it is and the store moves the zone and its racks.
                  const d = zoneDrag.current
                  if (!d) return pos
                  const total = { x: Math.round((pos.x - d.start.x) / view.scale / 100) * 100, y: Math.round((pos.y - d.start.y) / view.scale / 100) * 100 }
                  if (total.x !== d.applied.x || total.y !== d.applied.y) {
                    moveZone(i, total.x - d.applied.x, total.y - d.applied.y)
                    d.applied = total
                  }
                  return d.start
                }}
                onDragEnd={() => {
                  zoneDrag.current = null
                }}>
                <Line points={z.polygon.flatMap((p) => [p.x, p.y])} closed fill={ZONE_FILL[z.type]}
                  stroke={selected ? '#2563eb' : '#64748b'} strokeWidth={selected ? 2 : 1} dash={selected ? undefined : [6, 4]}
                  strokeScaleEnabled={false} />
                <Text x={bounds(z.polygon).minX + 8 * px} y={bounds(z.polygon).minY + 6 * px} text={`${z.code} · ${z.name}`}
                  fontSize={12 * px} fill="#475569" listening={false} />
              </Group>
            )
          })}
          {doc.racks.map((r, i) => {
            const t = templates[r.templateId]
            if (!t) return null
            const { widthMm, depthMm } = rackSize(r, t)
            return (
              <RackNode key={i} index={i} rack={r} w={widthMm} d={depthMm} fill={RACK_FILL[statuses[i] ?? 'ok']}
                selected={selection?.kind === 'rack' && selection.index === i} editable={editable}
                typeW={t.widthMm} typeD={t.depthMm} nodes={rackNodes} onLive={setLive} unit={unit} floor={t.style === 'FLOOR'} />
            )
          })}
          {editable && !drawing && (
            <Transformer ref={transformer} rotateEnabled keepRatio={false} flipEnabled={false} ignoreStroke
              rotationSnaps={[0, 45, 90, 135, 180, 225, 270, 315]} rotationSnapTolerance={4}
              enabledAnchors={['middle-left', 'middle-right', 'top-center', 'bottom-center']}
              anchorSize={9} borderStroke="#2563eb" anchorStroke="#2563eb" />
          )}
          {live && (
            <Label x={live.x} y={live.y} listening={false}>
              <Tag fill="#0f172a" opacity={0.85} cornerRadius={3 * px} />
              <Text text={live.text} fontSize={12 * px} padding={4 * px} fill="#fff" />
            </Label>
          )}
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
                <OpeningMark kind={o.kind} widthMm={o.widthMm} px={px} selected={selected} />
              </Group>
            )
          })}
          {doc.openings.map((o, i) => {
            // Label upright and on one line, nudged from the wall towards the middle of the floor.
            const c = centroid(doc.floor)
            const len = Math.hypot(c.x - o.x, c.y - o.y) || 1
            return (
              <Text key={'label' + i} x={o.x + ((c.x - o.x) / len) * Math.max(700, 22 * px)} y={o.y + ((c.y - o.y) / len) * Math.max(700, 22 * px)}
                text={o.label} fontSize={12 * px} fill="#0f172a" wrap="none" listening={false}
                offsetX={labelOffset(o.label, 12 * px, (c.x - o.x) / len)} offsetY={6 * px} />
            )
          })}
          {editable && !drawing && doc.floor.map((p, i) => (
            <Circle key={i} x={p.x} y={p.y} radius={6 * px} fill="#fff" stroke="#2563eb" strokeWidth={2}
              strokeScaleEnabled={false} draggable
              onDragMove={(e) => moveFloorPoint(i, { x: e.target.x(), y: e.target.y() })}
              onDragEnd={(e) => e.target.position(useEditor.getState().doc?.floor[i] ?? p)}
              onContextMenu={(e) => {
                e.evt.preventDefault()
                removeFloorPoint(i)
              }} />
          ))}
          {selection?.kind === 'zone' && doc.zones[selection.index] && edgeLabels(doc.zones[selection.index]!.polygon, unit, px, '#1d4ed8')}
          {editable && !drawing && selection?.kind === 'zone' && doc.zones[selection.index]?.polygon.map((p, j) => (
            <Rect key={'zp' + j} x={p.x} y={p.y} width={10 * px} height={10 * px} offsetX={5 * px} offsetY={5 * px}
              fill="#fff" stroke="#2563eb" strokeWidth={2} strokeScaleEnabled={false} draggable
              onDragMove={(e) => moveZonePoint(selection.index, j, { x: e.target.x(), y: e.target.y() })}
              onDragEnd={(e) => e.target.position(useEditor.getState().doc?.zones[selection.index]?.polygon[j] ?? p)} />
          ))}
        </Layer>
        {placing && ghost && (
          <Layer listening={false}>
            <Ghost placing={placing} at={ghost} px={px} />
          </Layer>
        )}
        {drawing && (
          <Layer listening={false}>
            <Line points={[...drawing.flatMap((p) => [p.x, p.y]), ...(cursor ? [cursor.x, cursor.y] : [])]}
              stroke="#2563eb" strokeWidth={2} dash={[8, 6]} strokeScaleEnabled={false} />
            {drawing.length > 0 && cursor && edgeLabels([drawing[drawing.length - 1]!, cursor], unit, px, '#2563eb', false)}
            {drawing.length > 1 && edgeLabels(drawing, unit, px, '#2563eb', false)}
            {drawing.map((p, i) => (
              <Circle key={i} x={p.x} y={p.y} radius={(i === 0 ? 8 : 5) * px} fill={i === 0 ? '#2563eb' : '#fff'}
                stroke="#2563eb" strokeWidth={2} strokeScaleEnabled={false} />
            ))}
          </Layer>
        )}
      </Stage>
      <ScaleBar scale={view.scale} unit={unit} />
    </div>
  )
}

/**
 * A door or dock on its wall (the group is turned along the wall). Never smaller than about 28 × 12 screen pixels,
 * so it can be seen and grabbed on a big floor zoomed out.
 */
function OpeningMark({ kind, widthMm, px, selected, ghost }: {
  kind: Opening['kind']; widthMm: number; px: number; selected: boolean; ghost?: boolean
}) {
  const w = Math.max(widthMm, 28 * px)
  const d = Math.max(600, 12 * px)
  return (
    <Rect x={-w / 2} y={-d / 2} width={w} height={d} fill={kind === 'DOCK' ? '#f59e0b' : '#3b82f6'}
      opacity={ghost ? 0.5 : 0.9} cornerRadius={2 * px} stroke={selected ? '#0f172a' : '#ffffff'}
      strokeWidth={selected ? 3 : 1} strokeScaleEnabled={false} hitStrokeWidth={10} />
  )
}

/** What will be put down where the pointer is: a door snapped onto the nearest wall, or a rack (red outside a zone). */
function Ghost({ placing, at, px }: { placing: Placing; at: { x: number; y: number }; px: number }) {
  const doc = useEditor.getState().doc
  if (!doc) return null
  if (placing.kind !== 'RACK') {
    const wall = nearestOnOutline(doc.floor, at)
    return (
      <Group x={wall.x} y={wall.y} rotation={wall.angle}>
        <OpeningMark kind={placing.kind} widthMm={placing.kind === 'DOCK' ? 3000 : 1200} px={px} selected={false} ghost />
      </Group>
    )
  }
  const p = { x: snap(at.x), y: snap(at.y) }
  const inside = doc.zones.some((z) => pointInPolygon(p, z.polygon))
  const { widthMm: w, depthMm: d } = placing.type
  return (
    <Rect x={p.x - w / 2} y={p.y - d / 2} width={w} height={d} fill={inside ? '#93c5fd' : '#fca5a5'} opacity={0.6}
      stroke={inside ? '#2563eb' : '#dc2626'} strokeWidth={2} strokeScaleEnabled={false} dash={[6, 4]} />
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

/**
 * Length of every edge, written along it in the chosen unit: the drawing shows real sizes, not just shapes.
 * closed=false labels an open path (the outline being drawn).
 */
function edgeLabels(polygon: { x: number; y: number }[], unit: Unit, px: number, fill: string, closed = true) {
  const n = closed ? polygon.length : polygon.length - 1
  const labels = []
  for (let i = 0; i < n; i++) {
    const a = polygon[i]!
    const b = polygon[(i + 1) % polygon.length]!
    const len = Math.hypot(b.x - a.x, b.y - a.y)
    if (len < 40 * px) continue // too short on screen to label
    let angle = (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI
    if (angle > 90 || angle <= -90) angle += 180 // keep text upright
    labels.push(
      <Text key={'e' + i} x={(a.x + b.x) / 2} y={(a.y + b.y) / 2} rotation={angle} text={formatWith(len, unit)}
        fontSize={11 * px} fill={fill} offsetY={14 * px} offsetX={formatWith(len, unit).length * 0.28 * 11 * px}
        listening={false} />,
    )
  }
  return labels
}

/** Bar of a round length (1, 2, 5 x 10^n in the chosen unit) about 120 px long. */
function ScaleBar({ scale, unit }: { scale: number; unit: Unit }) {
  const target = 120 / scale / UNITS[unit] // length in units that 120 px covers
  const pow = 10 ** Math.floor(Math.log10(target))
  const nice = [1, 2, 5, 10].map((f) => f * pow).filter((v) => v <= target).pop() ?? pow
  const widthPx = nice * UNITS[unit] * scale
  return (
    <div className="pointer-events-none absolute bottom-3 left-3 rounded bg-background/80 px-2 py-1 text-xs tabular-nums">
      <div className="h-1.5 border-x-2 border-b-2 border-foreground" style={{ width: widthPx }} />
      {nice} {unit}
    </div>
  )
}

/**
 * One rack. Memoized, and its label is sized in floor units (it scales with the drawing like a plan), so
 * zooming and panning redraw it without re-rendering 100s of React components.
 */
const RackNode = memo(function RackNode({ index: i, rack: r, w, d, fill, selected, editable, typeW, typeD, nodes, onLive, unit, floor }: {
  index: number
  rack: Rack
  w: number
  d: number
  fill: string
  selected: boolean
  editable: boolean
  typeW: number
  typeD: number
  nodes: RefObject<Map<number, Konva.Group>>
  onLive: (live: { x: number; y: number; text: string } | null) => void
  unit: Unit
  /** Floor storage row (v2.1): drawn dashed so it reads as pallets on the floor, not a rack. */
  floor?: boolean
}) {
  const { select, moveRack, updateRack } = useEditor.getState()
  const fontSize = Math.min(d * 0.6, w / (r.code.length * 0.62))
  return (
    <Group x={r.x} y={r.y} rotation={r.rotation} draggable={editable}
      ref={(node) => {
        if (node) nodes.current.set(i, node)
        else nodes.current.delete(i)
      }}
      onMouseDown={(e) => {
        e.cancelBubble = true // select the rack, not the zone underneath
        select({ kind: 'rack', index: i })
      }}
      onDragMove={(e) => moveRack(i, { x: e.target.x(), y: e.target.y() })}
      onDragEnd={(e) => {
        const placed = useEditor.getState().doc?.racks[i]
        if (placed) e.target.position({ x: placed.x, y: placed.y })
      }}
      onTransform={(e) => {
        // Live size and angle while dragging the handles: the length is worked out as you drag.
        const n = e.target
        const deg = ((Math.round(n.rotation()) % 360) + 360) % 360
        onLive({ x: n.x(), y: n.y(), text: `${formatWith(w * n.scaleX(), unit)} × ${formatWith(d * n.scaleY(), unit)} · ${deg}°` })
      }}
      onTransformEnd={(e) => {
        const n = e.target
        const widthMm = Math.min(30_000, Math.max(300, Math.round(w * n.scaleX())))
        const depthMm = Math.min(5_000, Math.max(300, Math.round(d * n.scaleY())))
        const rotation = ((Math.round(n.rotation()) % 360) + 360) % 360
        n.scale({ x: 1, y: 1 })
        onLive(null)
        updateRack(i, { rotation, widthMm: widthMm === typeW ? null : widthMm, depthMm: depthMm === typeD ? null : depthMm })
        moveRack(i, { x: n.x(), y: n.y() })
      }}>
      <Rect x={-w / 2} y={-d / 2} width={w} height={d} fill={fill} stroke={selected ? '#2563eb' : '#475569'}
        strokeWidth={selected ? 3 : 1} strokeScaleEnabled={false} perfectDrawEnabled={false} opacity={floor ? 0.6 : 1}
        dash={floor ? [8, 6] : undefined} />
      <Text x={-w / 2} y={-fontSize / 2} width={w} align="center" text={r.code} fontSize={fontSize} fill="#1e293b"
        listening={false} perfectDrawEnabled={false} />
    </Group>
  )
})
