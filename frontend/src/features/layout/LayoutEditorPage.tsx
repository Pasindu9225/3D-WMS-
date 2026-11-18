import { ArrowLeft, Box, Boxes, DoorOpen, Rocket, Maximize, PenLine, Save, Shapes, SquareDashed, Truck, Trash2 } from 'lucide-react'
import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { Link, useBlocker, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { usePublishLayout, useLayout, useRackTypes, useSaveLayout, useWarehouse } from '@/features/warehouses/api'
import { Dialog } from '@/components/ui/dialog'
import { ApiError, type Issue } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useEditor, type Selection } from './editorStore'
import { CornerTable, DrawingPanel, ShapeDialog } from './FloorPanels'
import { LengthInput } from './LengthInput'
import { areaM2, defaultHeight, rackStatuses } from './geometry'
import { formatArea, UNITS, type Unit } from './units'
import { RackPanel, ZonePanel } from './ZoneRackPanels'

// Konva only loads here, never in the main bundle.
const FloorCanvas = lazy(() => import('./FloorCanvas').then((m) => ({ default: m.FloorCanvas })))

export function LayoutEditorPage() {
  const { id = '' } = useParams()
  const me = useMe()
  const editable = can(me.data, 'warehouses:manage')
  const warehouse = useWarehouse(id)
  const layout = useLayout(id)
  const save = useSaveLayout(id)
  const publish = usePublishLayout(id)
  const [publishing, setPublishing] = useState(false)
  const doc = useEditor((s) => s.doc)
  const dirty = useEditor((s) => s.dirty)
  const baseRevision = useEditor((s) => s.baseRevision)
  const selection = useEditor((s) => s.selection)
  const drawing = useEditor((s) => s.drawing)
  const drawTarget = useEditor((s) => s.drawTarget)
  const placing = useEditor((s) => s.placing)
  const placeMissed = useEditor((s) => s.placeMissed)
  const { load, startPlacing, cancelPlacing, updateOpening, removeOpening, setUnit, startDrawing, finishDrawing, cancelDrawing,
    rotateRack, removeSelected } = useEditor.getState()
  const [fitSignal, setFitSignal] = useState(0)
  const [shaping, setShaping] = useState(false)
  const unit = useEditor((s) => s.unit)
  const rackTypes = useRackTypes()
  // Types used by the layout (even archived ones) plus the whole library for placing new racks.
  const templates = useMemo(
    () => ({ ...layout.data?.templates, ...Object.fromEntries((rackTypes.data?.items ?? []).map((rt) => [rt.id, rt])) }),
    [layout.data, rackTypes.data],
  )
  const statuses = useMemo(() => (doc ? rackStatuses(doc, templates) : []), [doc, templates])
  const badRacks = statuses.filter((s) => s === 'bad').length
  const narrowRacks = statuses.filter((s) => s === 'warn').length

  // Editing shortcuts (not while typing in a field): Delete removes the selected rack or door, R turns a rack,
  // Ctrl+C / Ctrl+V copy and paste the selected area (with its racks) or rack.
  useEffect(() => {
    if (!editable) return
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement || useEditor.getState().drawing) return
      const sel = useEditor.getState().selection
      if (e.key === 'Delete' || e.key === 'Backspace') removeSelected()
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c') useEditor.getState().copySelection()
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v') useEditor.getState().paste()
      else if ((e.key === 'r' || e.key === 'R') && sel?.kind === 'rack') rotateRack(sel.index, e.shiftKey ? 15 : 90)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [editable, removeSelected, rotateRack])

  useEffect(() => {
    if (layout.data) load(layout.data)
  }, [layout.data, load])

  const reload = () => {
    save.reset() // the old error belongs to the version being replaced
    void layout.refetch()
  }

  // Leaving with unsaved changes: ask inside the app, and let the browser ask on tab close.
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (useEditor.getState().dirty) e.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  // Esc stops click-to-place.
  useEffect(() => {
    if (!placing) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && cancelPlacing()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [placing, cancelPlacing])

  // Keyboard while drawing an outline: Enter finishes, Esc cancels.
  useEffect(() => {
    if (!drawing) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && !(e.target instanceof HTMLInputElement)) finishDrawing() // Enter in the length box adds a wall
      if (e.key === 'Escape') cancelDrawing()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [drawing, finishDrawing, cancelDrawing])

  const onSave = () => {
    publish.reset() // an older publish result no longer describes this draft
    if (doc) save.mutate({ baseRevision, layout: doc }, { onSuccess: (view) => load(view) })
  }
  const errors: Issue[] = save.error instanceof ApiError ? save.error.issues
    : publish.error instanceof ApiError ? publish.error.issues : []
  const published = layout.data?.status === 'PUBLISHED'
  const conflict = save.error instanceof ApiError && save.error.code === 'LAYOUT_CHANGED'
  const warnings = save.data?.warnings ?? layout.data?.warnings ?? []
  const opening = selection?.kind === 'opening' ? doc?.openings[selection.index] : undefined

  return (
    <div className="-m-4 flex h-[calc(100dvh-3.5rem)] flex-col sm:-m-6">
      <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-2">
        <Button asChild variant="ghost" size="icon" aria-label={t('layout.back')}>
          <Link to="/app/warehouses"><ArrowLeft /></Link>
        </Button>
        <div className="mr-auto min-w-0">
          <h1 className="truncate font-semibold">{warehouse.data ? `${warehouse.data.code} · ${warehouse.data.name}` : t('app.loading')}</h1>
          <p className="text-xs text-muted-foreground">
            {layout.data && t(`layout.status.${layout.data.status}`)}
            {baseRevision > 0 && ` · ${t('layout.revision', { n: baseRevision })}`}
            {doc && ` · ${t('layout.area', { area: formatArea(Math.round(areaM2(doc.floor) * 1_000_000), unit), unit })}`}
            {dirty && <span className="text-accent-foreground dark:text-accent"> · {t('layout.unsaved')}</span>}
          </p>
        </div>
        {editable && (
          <>
            <Button variant="outline" size="sm" disabled={!doc || !!drawing} onClick={() => setShaping(true)}>
              <Shapes /> {t('layout.shape')}
            </Button>
            <Button variant={drawing && drawTarget === 'floor' ? 'default' : 'outline'} size="sm"
              onClick={() => (drawing ? cancelDrawing() : startDrawing('floor'))}
              disabled={!doc || (!!drawing && drawTarget !== 'floor')} aria-pressed={!!drawing && drawTarget === 'floor'}>
              <PenLine /> {t('layout.draw')}
            </Button>
            <Button variant={drawing && drawTarget === 'zone' ? 'default' : 'outline'} size="sm"
              onClick={() => (drawing ? cancelDrawing() : startDrawing('zone'))}
              disabled={!doc || (!!drawing && drawTarget !== 'zone')} aria-pressed={!!drawing && drawTarget === 'zone'}>
              <SquareDashed /> {t('layout.drawZone')}
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" disabled={!doc || !!drawing}
                  aria-pressed={placing?.kind === 'RACK'}><Box /> {t('layout.addRack')}</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {doc && doc.zones.length === 0 ? (
                  <>
                    {/* Racks stand inside zones: say so instead of a greyed-out button nobody can explain. */}
                    <DropdownMenuItem disabled className="max-w-64 whitespace-normal">{t('layout.rack.needZone')}</DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => startDrawing('zone')}><SquareDashed /> {t('layout.drawZone')}</DropdownMenuItem>
                  </>
                ) : (
                  <>
                    {(rackTypes.data?.items ?? []).length === 0 && (
                      <DropdownMenuItem disabled>{t('layout.rack.noTypes')}</DropdownMenuItem>
                    )}
                    {(rackTypes.data?.items ?? []).map((rt) => (
                      <DropdownMenuItem key={rt.id} onSelect={() => startPlacing({ kind: 'RACK', type: rt })}>{rt.code} · {rt.name}</DropdownMenuItem>
                    ))}
                  </>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
            <Button variant="outline" size="sm" onClick={() => startPlacing({ kind: 'DOOR' })} disabled={!doc || !!drawing}
              aria-pressed={placing?.kind === 'DOOR'}><DoorOpen /> {t('layout.addDoor')}</Button>
            <Button variant="outline" size="sm" onClick={() => startPlacing({ kind: 'DOCK' })} disabled={!doc || !!drawing}
              aria-pressed={placing?.kind === 'DOCK'}><Truck /> {t('layout.addDock')}</Button>
          </>
        )}
        <Button variant="outline" size="sm" onClick={() => setFitSignal((n) => n + 1)}><Maximize /> {t('layout.fit')}</Button>
        <select aria-label={t('layout.unit')} value={unit} onChange={(e) => setUnit(e.target.value as Unit)}
          className="h-8 rounded-md border border-input bg-background px-2 text-sm">
          {(Object.keys(UNITS) as Unit[]).map((u) => <option key={u} value={u}>{u}</option>)}
        </select>
        {editable && (
          <Button size="sm" onClick={onSave} disabled={!dirty || save.isPending}>
            <Save /> {save.isPending ? t('common.saving') : t('layout.save')}
          </Button>
        )}
        {editable && (
          <Button size="sm" variant="outline" onClick={() => setPublishing(true)}
            disabled={dirty || layout.data?.status !== 'DRAFT' || publish.isPending}
            title={dirty ? t('layout.publish.saveFirst') : undefined}>
            <Rocket /> {t('layout.publish')}
          </Button>
        )}
        {published && (
          <Button asChild size="sm" variant="outline">
            <Link to={`/app/warehouses/${id}/bins`}><Boxes /> {t('layout.binsLink')}</Link>
          </Button>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <div className="relative min-h-[50vh] flex-1">
          {placing && (
            <div role="status" className="absolute bottom-3 left-1/2 z-10 flex -translate-x-1/2 items-center gap-3 rounded-md border bg-card px-3 py-1.5 text-sm shadow">
              <span className={placeMissed ? 'text-destructive' : undefined}>
                {placeMissed ? t('layout.place.missed') : t(`layout.place.${placing.kind}`)}
              </span>
              <Button size="sm" variant="ghost" onClick={cancelPlacing}>{t('layout.place.done')}</Button>
            </div>
          )}
          <Suspense fallback={<p className="p-6 text-sm text-muted-foreground">{t('app.loading')}</p>}>
            <FloorCanvas templates={templates} statuses={statuses} editable={editable} fitSignal={fitSignal} />
          </Suspense>
        </div>

        <aside className="w-full shrink-0 space-y-4 overflow-y-auto border-t bg-card p-4 md:w-80 md:border-t-0 md:border-l">
          {layout.isError && <FormMessage tone="error">{errorMessage(layout.error)}</FormMessage>}
          {conflict && (
            <FormMessage tone="error">
              {t('layout.conflict')}{' '}
              <button type="button" className="underline" onClick={reload}>{t('layout.reload')}</button>
            </FormMessage>
          )}
          {save.isError && !conflict && errors.length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
          {save.isSuccess && !dirty && !publish.isSuccess && <FormMessage tone="success">{t('layout.saved')}</FormMessage>}
          {publish.isSuccess && !dirty && (
            <FormMessage tone="success">{t('layout.publish.done', { bins: publish.data.bins, added: publish.data.binsAdded,
              removed: publish.data.binsRemoved, renamed: publish.data.binsRenamed, racks: publish.data.racks })}</FormMessage>
          )}
          {publish.isError && errors.length === 0 && <FormMessage tone="error">{errorMessage(publish.error)}</FormMessage>}

          {opening && selection?.kind === 'opening' && editable && (
            <section className="space-y-3 rounded-lg border p-3">
              <h2 className="text-sm font-semibold">{opening.kind === 'DOCK' ? t('layout.dock') : t('layout.door')}</h2>
              <Field label={t('layout.label')}>
                {(p) => <Input {...p} value={opening.label} maxLength={40}
                  onChange={(e) => updateOpening(selection.index, { label: e.target.value })} />}
              </Field>
              <label className="grid gap-1.5 text-sm font-medium">{t('layout.widthIn', { unit })}
                <LengthInput label={t('layout.widthIn', { unit })} mm={opening.widthMm} min={500} className="h-10 w-full"
                  onCommit={(mm) => updateOpening(selection.index, { widthMm: Math.min(20_000, mm) })} />
              </label>
              <label className="grid gap-1.5 text-sm font-medium">{t('layout.heightIn', { unit })}
                <LengthInput label={t('layout.heightIn', { unit })} mm={opening.heightMm ?? defaultHeight(opening.kind)} min={1500}
                  className="h-10 w-full" onCommit={(mm) => updateOpening(selection.index, { heightMm: Math.min(15_000, mm) })} />
              </label>
              <Button variant="outline" size="sm" className="text-destructive" onClick={() => removeOpening(selection.index)}>
                <Trash2 /> {t('layout.remove')}
              </Button>
            </section>
          )}

          {editable && selection?.kind === 'zone' && <ZonePanel index={selection.index} />}
          {editable && selection?.kind === 'rack' && <RackPanel index={selection.index} types={templates} />}
          <DrawingPanel />
          {(badRacks > 0 || narrowRacks > 0) && (
            <p role="status" className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm">
              {badRacks > 0 && t('layout.live.bad', { n: badRacks })}{' '}
              {narrowRacks > 0 && t('layout.live.narrow', { n: narrowRacks })}
            </p>
          )}
          <IssueList title={t('layout.errors')} issues={errors} tone="bad" />
          <IssueList title={t('layout.warnings')} issues={warnings} tone="warn" />
          {editable && !drawing && !selection && <CornerTable />}
          {editable && <p className="text-xs leading-relaxed text-muted-foreground">{t('layout.help')}</p>}
        </aside>
      </div>

      {shaping && <ShapeDialog onClose={() => setShaping(false)} />}
      {publishing && (
        <Dialog open onOpenChange={(o) => !o && setPublishing(false)} title={t('layout.publish.title')}
          description={t('layout.publish.confirm')}>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setPublishing(false)}>{t('common.cancel')}</Button>
            <Button disabled={publish.isPending} onClick={() => {
              save.reset()
              publish.mutate(baseRevision, { onSettled: () => setPublishing(false) })
            }}>
              <Rocket /> {publish.isPending ? t('layout.publish.working') : t('layout.publish')}
            </Button>
          </div>
        </Dialog>
      )}
      {blocker.state === 'blocked' && (
        <div role="alertdialog" aria-modal="true" className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4">
          <div className="max-w-sm space-y-4 rounded-lg border bg-card p-6">
            <p>{t('layout.leaveConfirm')}</p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => blocker.reset()}>{t('layout.stay')}</Button>
              <Button variant="destructive" onClick={() => blocker.proceed()}>{t('layout.leave')}</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function IssueList({ title, issues, tone }: { title: string; issues: Issue[]; tone: 'bad' | 'warn' }) {
  const doc = useEditor((s) => s.doc)
  if (issues.length === 0) return null
  // Clicking a problem selects the door, zone or rack it is about, so it can be moved or removed.
  const target = (ref: string): Selection => {
    if (!doc) return null
    const o = doc.openings.findIndex((x) => x.label === ref)
    if (o >= 0) return { kind: 'opening', index: o }
    const r = doc.racks.findIndex((x) => x.code === ref)
    if (r >= 0) return { kind: 'rack', index: r }
    const z = doc.zones.findIndex((x) => x.code === ref)
    return z >= 0 ? { kind: 'zone', index: z } : null
  }
  return (
    <section className="space-y-2">
      <h2 className="flex items-center gap-2 text-sm font-semibold">{title} <Badge tone={tone}>{issues.length}</Badge></h2>
      <ul className="space-y-1.5 text-sm">
        {issues.map((i, n) => (
          <li key={n}>
            {target(i.ref) ? (
              <button type="button" className="w-full rounded-md bg-secondary/60 px-2 py-1.5 text-left hover:bg-secondary"
                onClick={() => useEditor.getState().select(target(i.ref))}>{i.message}</button>
            ) : <p className="rounded-md bg-secondary/60 px-2 py-1.5">{i.message}</p>}
          </li>
        ))}
      </ul>
    </section>
  )
}
