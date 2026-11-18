import { create } from 'zustand'
import type { LayoutDocument, LayoutView, Opening, Point, Rack, RackType, Zone } from '@/features/warehouses/api'
import {
  bounds, defaultHeight, floorPreset, insertOnEdge, nearestOnOutline, nextCode, pointInPolygon, selfIntersects, snap,
  type FloorShape,
} from './geometry'
import { UNITS, type Unit } from './units'

const UNIT_KEY = 'wms.editor.unit'
/** Remembered per browser (a display preference only, so localStorage is fine and may be unavailable). */
function savedUnit(): Unit {
  try {
    const u = localStorage.getItem(UNIT_KEY)
    return u && u in UNITS ? (u as Unit) : 'm'
  } catch {
    return 'm'
  }
}

export type Selection =
  | { kind: 'opening'; index: number }
  | { kind: 'zone'; index: number }
  | { kind: 'rack'; index: number }
  | null

type Footprint = Pick<RackType, 'widthMm' | 'depthMm'>

/** What a click on the floor puts down while placing (click-to-place). */
export type Placing = { kind: 'DOOR' | 'DOCK' } | { kind: 'RACK'; type: RackType }

interface EditorState {
  doc: LayoutDocument | null
  /** Revision the editor started from; sent with the save so a newer save by someone else is detected. */
  baseRevision: number
  dirty: boolean
  selection: Selection
  /** Corners placed so far in a drawing mode; null when not drawing. */
  drawing: Point[] | null
  /** What the outline being drawn becomes: the floor, or a new zone. */
  drawTarget: 'floor' | 'zone'
  drawError: 'CROSSING' | 'TOO_FEW' | 'OUTSIDE' | null
  /** Where the pointer is while drawing (snapped); gives the direction for a typed length. */
  cursor: Point | null
  /** Click-to-place mode: the item follows the pointer and a click puts it down; null when off. */
  placing: Placing | null
  /** The last click while placing a rack missed every zone. */
  placeMissed: boolean
  startPlacing: (p: Placing) => void
  cancelPlacing: () => void
  /** Puts the item being placed at p (doors snap to the nearest wall; racks must be inside a zone). */
  place: (p: Point) => void
  unit: Unit
  setUnit: (unit: Unit) => void
  setCursor: (p: Point | null) => void
  /** Next corner at an exact distance from the last one, towards the pointer (direction snapped to 15°). */
  addDrawLength: (mm: number) => void
  /** Shape tool: a sized preset becomes the floor, or a new zone centred on the floor. */
  placeShape: (shape: FloorShape, widthMm: number, lengthMm: number, target: 'floor' | 'zone') => void
  load: (view: LayoutView) => void
  /** exact: typed position, kept to the mm instead of snapping to the 0.1 m grid. */
  moveFloorPoint: (index: number, p: Point, exact?: boolean) => void
  insertFloorPoint: (p: Point) => void
  removeFloorPoint: (index: number) => void
  insertCornerAfter: (index: number) => void
  applyShape: (shape: FloorShape) => void
  startDrawing: (target?: 'floor' | 'zone') => void
  addDrawPoint: (p: Point) => void
  finishDrawing: () => void
  cancelDrawing: () => void
  addOpening: (kind: Opening['kind'], at?: Point) => void
  moveOpening: (index: number, p: Point) => void
  updateOpening: (index: number, patch: Partial<Pick<Opening, 'label' | 'widthMm' | 'heightMm' | 'kind'>>) => void
  removeOpening: (index: number) => void
  updateZone: (index: number, patch: Partial<Pick<Zone, 'code' | 'name' | 'type'>>) => void
  moveZonePoint: (zone: number, point: number, p: Point) => void
  removeZone: (index: number) => void
  /** Moves the zone and the racks standing in it. */
  moveZone: (index: number, dx: number, dy: number) => void
  /** Stretches the zone to a new overall width x length, keeping its top-left corner. */
  resizeZone: (index: number, widthMm: number, lengthMm: number) => void
  /** Copy of the zone (and its racks) right next to it, with new codes. */
  duplicateZone: (index: number) => void
  duplicateRack: (index: number, fp: Footprint) => void
  /** Ctrl+C / Ctrl+V: copy the selected zone (with racks) or rack; each paste lands 1 m further on. */
  clipboard: { zone: Zone; racks: Rack[] } | { rack: Rack } | null
  pasteCount: number
  copySelection: () => void
  paste: () => void
  /** at: where it goes (must be inside a zone); without it, the middle of the selected or first zone. */
  addRack: (type: RackType, at?: Point) => boolean
  /** exact: a typed centre, kept to the mm instead of the 0.1 m grid. */
  moveRack: (index: number, p: Point, exact?: boolean) => void
  /** Turns clockwise by step degrees (default 90). */
  rotateRack: (index: number, step?: number) => void
  /**
   * Builds a block from one rack: count more copies along its length (no gap), and rows-1 more rows
   * beside it, each its depth plus aisleMm further on. Works at any angle.
   */
  repeatRack: (index: number, count: number, fp: Footprint, rows?: number, aisleMm?: number) => void
  /** Code, type, exact angle or this rack's own size (null size = back to the type size). */
  updateRack: (index: number, patch: Partial<Pick<Rack, 'code' | 'templateId' | 'rotation' | 'widthMm' | 'depthMm' | 'heightMm'>>) => void
  removeRack: (index: number) => void
  removeSelected: () => void
  select: (selection: Selection) => void
}

/** Client-side copy of the layout being edited. Only this store changes it; the canvas just draws it. */
export const useEditor = create<EditorState>((set, get) => {
  const change = (fn: (doc: LayoutDocument) => LayoutDocument) => {
    const doc = get().doc
    if (doc) set({ doc: fn(doc), dirty: true })
  }
  /** New outline: doors and docks move to the nearest point of the new walls. */
  const withFloor = (d: LayoutDocument, floor: Point[]): LayoutDocument => ({
    ...d,
    floor,
    openings: d.openings.map((o) => {
      const at = nearestOnOutline(floor, o)
      return { ...o, x: at.x, y: at.y }
    }),
  })
  const setFloor = (floor: Point[]) => change((d) => withFloor(d, floor))
  const addZone = (polygon: Point[]) =>
    change((d) => {
      const code = nextCode('Z', d.zones.map((z) => z.code))
      set({ selection: { kind: 'zone', index: d.zones.length } })
      return { ...d, zones: [...d.zones, { code, type: 'STORAGE', name: `Zone ${code.slice(1)}`, polygon }] }
    })
  /** Adds a copy of a zone and its racks, shifted by off, with fresh zone and rack codes. */
  const pasteZone = (zone: Zone, racks: Rack[], off: Point) =>
    change((d) => {
      const code = nextCode('Z', d.zones.map((z) => z.code))
      const clamp = (v: number) => Math.min(1_000_000, Math.max(0, v))
      const used = d.racks.map((r) => r.code)
      const copies = racks.map((r) => {
        const rc = nextCode('R', used, 2)
        used.push(rc)
        return { ...r, code: rc, zone: code, x: clamp(r.x + off.x), y: clamp(r.y + off.y) }
      })
      set({ selection: { kind: 'zone', index: d.zones.length } })
      return {
        ...d,
        zones: [...d.zones, { ...zone, code, name: (zone.name + ' copy').slice(0, 60), polygon: zone.polygon.map((p) => ({ x: clamp(p.x + off.x), y: clamp(p.y + off.y) })) }],
        racks: [...d.racks, ...copies],
      }
    })
  /** The zone a rack centre falls in; racks keep their zone when dropped outside every zone (flagged red). */
  const zoneAt = (d: LayoutDocument, p: Point, fallback: string) =>
    d.zones.find((z) => pointInPolygon(p, z.polygon))?.code ?? fallback

  return {
    doc: null,
    baseRevision: 0,
    dirty: false,
    selection: null,
    drawing: null,
    drawTarget: 'floor',
    drawError: null,
    cursor: null,
    placing: null,
    placeMissed: false,
    startPlacing: (p) => set({ placing: p, placeMissed: false, drawing: null, selection: null }),
    cancelPlacing: () => set({ placing: null, placeMissed: false }),
    place: (p) => {
      const placing = get().placing
      if (!placing) return
      if (placing.kind === 'RACK') set({ placeMissed: !get().addRack(placing.type, p) })
      else get().addOpening(placing.kind, p)
    },
    unit: savedUnit(),
    setUnit: (unit) => {
      try {
        localStorage.setItem(UNIT_KEY, unit)
      } catch {
        // private window: the choice lasts until reload
      }
      set({ unit })
    },
    setCursor: (cursor) => set({ cursor }),
    addDrawLength: (mm) => {
      const { drawing, cursor } = get()
      const last = drawing?.[drawing.length - 1]
      if (!drawing || !last || mm <= 0) return
      const raw = cursor ? Math.atan2(cursor.y - last.y, cursor.x - last.x) : 0
      const step = Math.PI / 12 // 15°: walls at 0/45/90° come out exact
      const angle = Math.round(raw / step) * step
      // Whole mm, not the 0.1 m grid: a typed 50 ft wall must be exactly 50 ft.
      const point = {
        x: Math.min(1_000_000, Math.max(0, Math.round(last.x + mm * Math.cos(angle)))),
        y: Math.min(1_000_000, Math.max(0, Math.round(last.y + mm * Math.sin(angle)))),
      }
      set({ drawing: [...drawing, point], drawError: null })
    },
    placeShape: (shape, widthMm, lengthMm, target) => {
      const doc = get().doc
      if (!doc) return
      const b = bounds(doc.floor)
      const origin = target === 'floor'
        ? { x: b.minX, y: b.minY }
        : { x: Math.max(0, Math.round((b.minX + b.maxX - widthMm) / 2)), y: Math.max(0, Math.round((b.minY + b.maxY - lengthMm) / 2)) }
      const polygon = floorPreset(shape, widthMm, lengthMm).map((p) => ({ x: p.x + origin.x, y: p.y + origin.y }))
      if (target === 'floor') setFloor(polygon)
      else addZone(polygon)
    },
    load: (view) =>
      set({ doc: view.layout, baseRevision: view.revision, dirty: false, selection: null, drawing: null, drawError: null, placing: null }),
    moveFloorPoint: (index, p, exact) =>
      change((d) => withFloor(d,
        d.floor.map((pt, i) => (i !== index ? pt : exact ? { x: Math.round(p.x), y: Math.round(p.y) } : { x: snap(p.x), y: snap(p.y) })))),
    insertFloorPoint: (p) => change((d) => withFloor(d, insertOnEdge(d.floor, p))),
    removeFloorPoint: (index) =>
      change((d) => (d.floor.length <= 3 ? d : withFloor(d, d.floor.filter((_, i) => i !== index)))),
    insertCornerAfter: (index) =>
      change((d) => {
        const a = d.floor[index]!
        const b = d.floor[(index + 1) % d.floor.length]!
        const mid = { x: snap((a.x + b.x) / 2), y: snap((a.y + b.y) / 2) }
        return withFloor(d, [...d.floor.slice(0, index + 1), mid, ...d.floor.slice(index + 1)])
      }),
    applyShape: (shape) => {
      const doc = get().doc
      if (!doc) return
      const b = bounds(doc.floor) // same overall size as now, new outline
      setFloor(floorPreset(shape, b.maxX - b.minX, b.maxY - b.minY).map((p) => ({ x: p.x + b.minX, y: p.y + b.minY })))
    },
    startDrawing: (target = 'floor') => set({ drawing: [], drawTarget: target, drawError: null, selection: null, placing: null }),
    addDrawPoint: (p) => {
      const drawing = get().drawing
      if (!drawing) return
      const point = { x: snap(p.x), y: snap(p.y) }
      const last = drawing[drawing.length - 1]
      if (last && last.x === point.x && last.y === point.y) return // double-click lands twice on the same spot
      set({ drawing: [...drawing, point], drawError: null })
    },
    finishDrawing: () => {
      const { drawing, drawTarget, doc } = get()
      if (!drawing || !doc) return
      if (drawing.length < 3) return set({ drawError: 'TOO_FEW' })
      if (selfIntersects(drawing)) return set({ drawError: 'CROSSING' })
      if (drawTarget === 'zone') {
        // Corners on the wall count as inside; the server's exact check runs on save.
        const onOrIn = (p: Point) => pointInPolygon(p, doc.floor) || nearestOnOutline(doc.floor, p).distance < 1
        if (!drawing.every(onOrIn)) return set({ drawError: 'OUTSIDE' })
        addZone(drawing)
      } else {
        setFloor(drawing)
      }
      set({ drawing: null, drawError: null })
    },
    cancelDrawing: () => set({ drawing: null, drawError: null }),
    addOpening: (kind, where) =>
      change((d) => {
        const name = kind === 'DOCK' ? 'Dock' : 'Door'
        let n = d.openings.filter((o) => o.kind === kind).length + 1
        while (d.openings.some((o) => o.label === name + ' ' + n)) n++ // labels stay unique: problems name them
        const first = d.floor[0]!
        const second = d.floor[1]!
        const at = nearestOnOutline(d.floor, where ?? { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 })
        const opening: Opening = {
          kind,
          label: `${name} ${n}`,
          x: at.x,
          y: at.y,
          widthMm: kind === 'DOCK' ? 3000 : 1200,
          heightMm: defaultHeight(kind),
        }
        set({ selection: { kind: 'opening', index: d.openings.length } })
        return { ...d, openings: [...d.openings, opening] }
      }),
    moveOpening: (index, p) =>
      change((d) => {
        const at = nearestOnOutline(d.floor, p) // doors and docks always sit on the outer wall
        return { ...d, openings: d.openings.map((o, i) => (i === index ? { ...o, x: at.x, y: at.y } : o)) }
      }),
    updateOpening: (index, patch) =>
      change((d) => ({ ...d, openings: d.openings.map((o, i) => (i === index ? { ...o, ...patch } : o)) })),
    removeOpening: (index) => {
      change((d) => ({ ...d, openings: d.openings.filter((_, i) => i !== index) }))
      set({ selection: null })
    },
    updateZone: (index, patch) =>
      change((d) => {
        const old = d.zones[index]!.code
        const renamed = patch.code !== undefined && patch.code !== old
        return {
          ...d,
          zones: d.zones.map((z, i) => (i === index ? { ...z, ...patch } : z)),
          racks: renamed ? d.racks.map((r) => (r.zone === old ? { ...r, zone: patch.code! } : r)) : d.racks,
        }
      }),
    moveZonePoint: (zone, point, p) =>
      change((d) => ({
        ...d,
        zones: d.zones.map((z, i) => (i !== zone ? z : {
          ...z,
          polygon: z.polygon.map((pt, j) => (j === point ? { x: snap(p.x), y: snap(p.y) } : pt)),
        })),
      })),
    moveZone: (index, dx, dy) =>
      change((d) => {
        const z = d.zones[index]!
        const b = bounds(z.polygon)
        // Keep everything on the plan (coordinates are 0..1,000 m).
        const mx = Math.max(-b.minX, Math.min(dx, 1_000_000 - b.maxX))
        const my = Math.max(-b.minY, Math.min(dy, 1_000_000 - b.maxY))
        return {
          ...d,
          zones: d.zones.map((zz, i) => (i !== index ? zz : { ...zz, polygon: zz.polygon.map((p) => ({ x: p.x + mx, y: p.y + my })) })),
          racks: d.racks.map((r) => (r.zone === z.code ? { ...r, x: r.x + mx, y: r.y + my } : r)),
        }
      }),
    resizeZone: (index, widthMm, lengthMm) =>
      change((d) => {
        const z = d.zones[index]!
        const b = bounds(z.polygon)
        const sx = widthMm / (b.maxX - b.minX || 1)
        const sy = lengthMm / (b.maxY - b.minY || 1)
        const polygon = z.polygon.map((p) => ({
          x: Math.min(1_000_000, Math.round(b.minX + (p.x - b.minX) * sx)),
          y: Math.min(1_000_000, Math.round(b.minY + (p.y - b.minY) * sy)),
        }))
        return { ...d, zones: d.zones.map((zz, i) => (i === index ? { ...zz, polygon } : zz)) }
      }),
    duplicateZone: (index) => {
      const d = get().doc
      if (!d) return
      const z = d.zones[index]!
      const b = bounds(z.polygon)
      const floor = bounds(d.floor)
      // Beside it if that still fits on the floor, otherwise below it.
      const off = b.maxX + (b.maxX - b.minX) <= floor.maxX ? { x: b.maxX - b.minX, y: 0 } : { x: 0, y: b.maxY - b.minY }
      pasteZone(z, d.racks.filter((r) => r.zone === z.code), off)
    },
    duplicateRack: (index, fp) => get().repeatRack(index, 1, fp),
    clipboard: null,
    pasteCount: 0,
    copySelection: () => {
      const { doc: d, selection: sel } = get()
      if (!d || !sel) return
      if (sel.kind === 'zone') {
        const zone = d.zones[sel.index]!
        set({ clipboard: { zone, racks: d.racks.filter((r) => r.zone === zone.code) }, pasteCount: 0 })
      }
      if (sel.kind === 'rack') set({ clipboard: { rack: d.racks[sel.index]! }, pasteCount: 0 })
    },
    paste: () => {
      const { clipboard: c, pasteCount } = get()
      if (!c) return
      const step = (pasteCount + 1) * 1000
      set({ pasteCount: pasteCount + 1 })
      if ('zone' in c) return pasteZone(c.zone, c.racks, { x: step, y: step })
      change((d) => {
        const at = { x: Math.min(1_000_000, c.rack.x + step), y: Math.min(1_000_000, c.rack.y + step) }
        const rack = { ...c.rack, ...at, code: nextCode('R', d.racks.map((r) => r.code), 2), zone: zoneAt(d, at, c.rack.zone) }
        set({ selection: { kind: 'rack', index: d.racks.length } })
        return { ...d, racks: [...d.racks, rack] }
      })
    },
    removeZone: (index) => {
      // Racks cannot exist outside a zone, so they go with it (the panel says how many first).
      change((d) => {
        const code = d.zones[index]!.code
        return { ...d, zones: d.zones.filter((_, i) => i !== index), racks: d.racks.filter((r) => r.zone !== code) }
      })
      set({ selection: null })
    },
    addRack: (type, where) => {
      let added = false
      change((d) => {
        const sel = get().selection
        const at = where && { x: snap(where.x), y: snap(where.y) }
        const zone = at ? d.zones.find((z) => pointInPolygon(at, z.polygon))
          : sel?.kind === 'zone' ? d.zones[sel.index] : d.zones[0]
        if (!zone) return d
        const b = bounds(zone.polygon)
        const rack: Rack = {
          code: nextCode('R', d.racks.map((r) => r.code), 2),
          templateId: type.id,
          zone: zone.code,
          x: at?.x ?? snap((b.minX + b.maxX) / 2),
          y: at?.y ?? snap((b.minY + b.maxY) / 2),
          rotation: 0,
        }
        added = true
        set({ selection: { kind: 'rack', index: d.racks.length } })
        return { ...d, racks: [...d.racks, rack] }
      })
      return added
    },
    moveRack: (index, p, exact) =>
      change((d) => {
        const at = exact
          ? { x: Math.min(1_000_000, Math.max(0, Math.round(p.x))), y: Math.min(1_000_000, Math.max(0, Math.round(p.y))) }
          : { x: snap(p.x), y: snap(p.y) }
        return {
          ...d,
          racks: d.racks.map((r, i) => (i === index ? { ...r, ...at, zone: zoneAt(d, at, r.zone) } : r)),
        }
      }),
    rotateRack: (index, step = 90) =>
      change((d) => ({
        ...d,
        racks: d.racks.map((r, i) => (i === index ? { ...r, rotation: (((r.rotation + step) % 360) + 360) % 360 } : r)),
      })),
    repeatRack: (index, count, fp, rows = 1, aisleMm = 0) =>
      change((d) => {
        const base = d.racks[index]!
        // Along its length and across it, at its own angle; whole mm keeps straight rows exact.
        const length = base.widthMm ?? fp.widthMm
        const across = (base.depthMm ?? fp.depthMm) + aisleMm
        const a = (base.rotation * Math.PI) / 180
        const cos = base.rotation % 90 === 0 ? Math.round(Math.cos(a)) : Math.cos(a)
        const sin = base.rotation % 90 === 0 ? Math.round(Math.sin(a)) : Math.sin(a)
        const used = d.racks.map((r) => r.code)
        const copies: Rack[] = []
        for (let row = 0; row < rows; row++) {
          for (let n = 0; n <= count; n++) {
            if (row === 0 && n === 0) continue // the rack itself
            const at = {
              x: Math.round(base.x + n * length * cos - row * across * sin),
              y: Math.round(base.y + n * length * sin + row * across * cos),
            }
            if (at.x < 0 || at.y < 0 || at.x > 1_000_000 || at.y > 1_000_000) continue
            const code = nextCode('R', used, 2)
            used.push(code)
            copies.push({ ...base, ...at, code, zone: zoneAt(d, at, base.zone) })
          }
        }
        return { ...d, racks: [...d.racks, ...copies] }
      }),
    updateRack: (index, patch) =>
      change((d) => ({ ...d, racks: d.racks.map((r, i) => (i === index ? { ...r, ...patch } : r)) })),
    removeRack: (index) => {
      change((d) => ({ ...d, racks: d.racks.filter((_, i) => i !== index) }))
      set({ selection: null })
    },
    removeSelected: () => {
      const sel = get().selection
      if (sel?.kind === 'rack') get().removeRack(sel.index)
      if (sel?.kind === 'opening') get().removeOpening(sel.index)
      // Zones are not removed by the Delete key: that would also remove their racks.
    },
    select: (selection) => set({ selection }),
  }
})
