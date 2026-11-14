import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { FormMessage } from '@/components/ui/input'
import { t, type MessageKey } from '@/lib/i18n'
import { useEditor } from './editorStore'
import { bounds, type FloorShape } from './geometry'
import { LengthInput } from './LengthInput'
import { formatWith } from './units'

/** Shown while the planner clicks a new outline onto the grid, or types wall lengths. */
export function DrawingPanel() {
  const drawing = useEditor((s) => s.drawing)
  const drawError = useEditor((s) => s.drawError)
  const zone = useEditor((s) => s.drawTarget === 'zone')
  const unit = useEditor((s) => s.unit)
  const { finishDrawing, cancelDrawing, addDrawLength } = useEditor.getState()
  if (!drawing) return null
  return (
    <section className="space-y-3 rounded-lg border border-primary/40 bg-primary/5 p-3">
      <h2 className="text-sm font-semibold">{zone ? t('layout.drawing.zoneTitle') : t('layout.drawing.title')}</h2>
      <p className="text-sm text-muted-foreground">{t('layout.drawing.help')}</p>
      <p className="text-sm font-medium" aria-live="polite">{t('layout.drawing.count', { n: drawing.length })}</p>
      {drawing.length > 0 && (
        <div className="space-y-1">
          <label className="flex items-center gap-2 text-sm">
            {t('layout.drawing.length', { unit })}
            <LengthInput mm={null} min={1} autoFocus clearOnCommit label={t('layout.drawing.length', { unit })}
              onCommit={addDrawLength} className="w-28" />
          </label>
          <p className="text-xs text-muted-foreground">{t('layout.drawing.lengthHint')}</p>
        </div>
      )}
      {drawError && <FormMessage tone="error">{t(`layout.drawing.${drawError}` as MessageKey)}</FormMessage>}
      <div className="flex gap-2">
        <Button size="sm" onClick={finishDrawing} disabled={drawing.length < 3}>{t('layout.drawing.finish')}</Button>
        <Button size="sm" variant="outline" onClick={cancelDrawing}>{t('layout.drawing.cancel')}</Button>
      </div>
    </section>
  )
}

/** Exact corner positions in the chosen unit: the precise way to copy a measured building. */
export function CornerTable() {
  // Select a stable reference: `?? []` inside the selector makes a new array per call and loops forever.
  const floor = useEditor((s) => s.doc?.floor) ?? []
  const unit = useEditor((s) => s.unit)
  const { moveFloorPoint, insertCornerAfter, removeFloorPoint } = useEditor.getState()
  const b = floor.length ? bounds(floor) : null
  // Typed corners are exact (whole mm), not snapped to the drawing grid.
  const set = (i: number, axis: 'x' | 'y', mm: number) => {
    const p = floor[i]!
    moveFloorPoint(i, { ...p, [axis]: mm }, true)
  }
  return (
    <section className="space-y-2">
      <h2 className="text-sm font-semibold">{t('layout.corners', { unit })}</h2>
      {b && <p className="text-xs tabular-nums">{t('layout.corners.size', { w: formatWith(b.maxX - b.minX, unit), l: formatWith(b.maxY - b.minY, unit) })}</p>}
      <p className="text-xs text-muted-foreground">{t('layout.corners.hint')}</p>
      <ol className="space-y-1.5">
        {floor.map((p, i) => (
          <li key={i} className="flex items-center gap-1.5">
            <span className="w-5 text-right text-xs tabular-nums text-muted-foreground">{i + 1}</span>
            <LengthInput label={`X ${i + 1}`} mm={p.x} onCommit={(mm) => set(i, 'x', mm)} />
            <LengthInput label={`Y ${i + 1}`} mm={p.y} onCommit={(mm) => set(i, 'y', mm)} />
            <Button variant="ghost" size="icon" className="size-8" aria-label={t('layout.corners.add', { n: i + 1 })}
              onClick={() => insertCornerAfter(i)}><Plus /></Button>
            <Button variant="ghost" size="icon" className="size-8" aria-label={t('layout.corners.remove', { n: i + 1 })}
              disabled={floor.length <= 3} onClick={() => removeFloorPoint(i)}><Trash2 /></Button>
          </li>
        ))}
      </ol>
    </section>
  )
}

const SHAPES: FloorShape[] = ['RECT', 'L', 'U', 'T', 'TRIANGLE', 'OVAL']

/** Shape tool: pick a shape, type its size in any unit, use it as the floor or as a new area. */
export function ShapeDialog({ onClose }: { onClose: () => void }) {
  const doc = useEditor((s) => s.doc)
  const unit = useEditor((s) => s.unit)
  const b = doc ? bounds(doc.floor) : { minX: 0, minY: 0, maxX: 20_000, maxY: 10_000 }
  const [shape, setShape] = useState<FloorShape>('RECT')
  const [target, setTarget] = useState<'floor' | 'zone'>('zone')
  const [w, setW] = useState(target === 'zone' ? Math.round((b.maxX - b.minX) / 4) : b.maxX - b.minX)
  const [l, setL] = useState(Math.round((b.maxY - b.minY) / 2))
  const place = () => {
    useEditor.getState().placeShape(shape, w, l, target)
    onClose()
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('layout.shapeTool')} description={t('layout.shapeTool.hint')}>
      <div className="grid gap-4">
        <fieldset className="grid grid-cols-3 gap-2">
          <legend className="sr-only">{t('layout.shape')}</legend>
          {SHAPES.map((s) => (
            <label key={s} className="flex cursor-pointer items-center gap-2 rounded-md border p-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-primary/10">
              <input type="radio" name="shape" value={s} checked={shape === s} onChange={() => setShape(s)} />
              {t(`layout.shape.${s}` as MessageKey)}
            </label>
          ))}
        </fieldset>
        <div className="flex flex-wrap gap-4">
          <label className="grid gap-1 text-sm font-medium">{t('layout.shapeTool.width', { unit })}
            <LengthInput label={t('layout.shapeTool.width', { unit })} mm={w} min={500} live onCommit={setW} className="h-10 w-32" />
          </label>
          <label className="grid gap-1 text-sm font-medium">{t('layout.shapeTool.length', { unit })}
            <LengthInput label={t('layout.shapeTool.length', { unit })} mm={l} min={500} live onCommit={setL} className="h-10 w-32" />
          </label>
        </div>
        <fieldset className="flex flex-wrap gap-4 text-sm">
          <legend className="mb-1 font-medium">{t('layout.shapeTool.useAs')}</legend>
          <label className="flex items-center gap-2"><input type="radio" name="target" checked={target === 'zone'} onChange={() => setTarget('zone')} /> {t('layout.shapeTool.zone')}</label>
          <label className="flex items-center gap-2"><input type="radio" name="target" checked={target === 'floor'} onChange={() => setTarget('floor')} /> {t('layout.shapeTool.floor')}</label>
        </fieldset>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={place} disabled={!doc}>{t('layout.shapeTool.place')}</Button>
        </div>
      </div>
    </Dialog>
  )
}
