import { ArrowLeft, Cuboid, Map, RotateCcw, Scan, Search, Square, ZoomIn, ZoomOut } from 'lucide-react'
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useParams, useSearchParams } from 'react-router'
import { useRoute } from './route'
import { Button } from '@/components/ui/button'
import { FormMessage } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { useWarehouse, useWarehouses } from '@/features/warehouses/api'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useScene } from './api'
import { useLiveBins } from './live'
import { shortcut, viewpoint, type CameraApi } from './camera'
import { BinPanel } from './BinPanel'
import { binColors, legend, type ColorMode } from './colors'
import { LiteMap, RackBins } from './LiteMap'
import { buildInstances, floorBox } from './sceneLayout'
import { findBin, suggestions } from './search'

// three.js and React Three Fiber load only here, never in the main bundle (SPEC v0.3).
const SceneCanvas = lazy(() => import('./SceneCanvas').then((m) => ({ default: m.SceneCanvas })))

const MODE_KEY = 'wms.3d.colorMode'
const VIEW_KEY = 'wms.3d.view'
type ViewMode = '3d' | 'lite'
/** 3D or Lite map, per browser: a weak PC that chose Lite should not download three.js again. */
function savedView(): ViewMode {
  try {
    return localStorage.getItem(VIEW_KEY) === 'lite' ? 'lite' : '3d'
  } catch {
    return '3d'
  }
}
/** Colour mode is a per-browser preference; storage may be unavailable (private window). */
function savedMode(): ColorMode {
  try {
    const m = localStorage.getItem(MODE_KEY)
    return m === 'status' || m === 'expiry' || m === 'heat' ? m : 'zone'
  } catch {
    return 'zone'
  }
}

/** /app/3d: open the only published warehouse straight away, otherwise let the user pick one. */
export function View3DIndexPage() {
  const list = useWarehouses()
  const published = list.data?.items.filter((w) => w.layoutStatus === 'PUBLISHED') ?? []
  if (published.length === 1) return <Navigate to={`/app/warehouses/${published[0]!.id}/3d`} replace />
  return (
    <div className="grid gap-4">
      <h1 className="text-2xl font-semibold">{t('view3d.title')}</h1>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      {list.data && published.length === 0 && <p className="text-muted-foreground">{t('view3d.nonePublished')}</p>}
      <ul className="grid gap-2 sm:grid-cols-2">
        {published.map((w) => (
          <li key={w.id}>
            <Link to={`/app/warehouses/${w.id}/3d`} className="block rounded-lg border bg-card p-4 hover:bg-secondary/60">
              <span className="font-mono font-medium">{w.code}</span> · {w.name}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function View3DPage() {
  const { id = '' } = useParams()
  const [search] = useSearchParams()
  const routeQuery = useRoute(search.get('route'))
  const route = routeQuery.data && routeQuery.data.warehouseId === id ? routeQuery.data : null
  const me = useMe()
  const warehouse = useWarehouse(id)
  const scene = useScene(id)
  const live = useLiveBins(id, !!scene.data)
  const notPublished = scene.error instanceof ApiError && scene.error.code === 'NOT_PUBLISHED'
  const camera = useRef<CameraApi | null>(null)
  const onCamera = useCallback((api: CameraApi) => { camera.current = api }, [])
  const floor = scene.data?.floor
  const parts = useMemo(() => (scene.data ? buildInstances(scene.data) : null), [scene.data])
  const [selected, setSelected] = useState<number | null>(null)
  const [query, setQuery] = useState('')
  const [notFound, setNotFound] = useState<string | null>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const codes = useMemo(() => scene.data?.bins.codes ?? [], [scene.data])
  const hints = useMemo(() => suggestions(codes, query), [codes, query])
  const [mode, setModeState] = useState<ColorMode>(savedMode)
  const setMode = (m: ColorMode) => {
    setModeState(m)
    try {
      localStorage.setItem(MODE_KEY, m)
    } catch {
      // private window: the choice lasts until reload
    }
  }
  const colors = useMemo(() => (scene.data ? binColors(scene.data, mode, scene.data.flags) : []), [scene.data, mode])
  const [view, setViewState] = useState<ViewMode>(savedView)
  const [slow, setSlow] = useState(false)
  const [rack, setRack] = useState<number | null>(null)
  const setView = (v: ViewMode) => {
    setViewState(v)
    setSlow(false)
    try {
      localStorage.setItem(VIEW_KEY, v)
    } catch {
      // private window: the choice lasts until reload
    }
  }
  const onSlow = useCallback(() => setSlow(true), [])

  const find = (text: string) => {
    const i = findBin(codes, text)
    setNotFound(i === null ? text.trim().toUpperCase() : null)
    if (i === null || !parts) return
    setSelected(i)
    setRack(scene.data!.bins.rack[i]!)
    const b = parts.bins[i]!
    camera.current?.flyTo(b, viewpoint(b))
  }

  // Keyboard: + / - zoom, T top view, R or Home reset, arrows pan (Shift: faster). Not while typing.
  useEffect(() => {
    if (!floor) return
    const step = floorBox(floor).size * 0.05
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setSelected(null)
        setRack(null)
        return
      }
      if (e.key === '/' && !(e.target instanceof HTMLInputElement)) {
        e.preventDefault()
        searchBox.current?.focus()
        return
      }
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement || e.ctrlKey || e.metaKey || e.altKey) return
      const action = camera.current && shortcut(e.key, e.shiftKey, camera.current, step)
      if (action) {
        e.preventDefault() // arrows would scroll the page
        action()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [floor])
  return (
    <div className="-m-4 flex h-[calc(100dvh-3.5rem)] flex-col sm:-m-6">
      <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-2">
        <Button asChild variant="ghost" size="icon" aria-label={t('layout.back')}>
          <Link to="/app/warehouses"><ArrowLeft /></Link>
        </Button>
        <div className="mr-auto min-w-0">
          <h1 className="truncate font-semibold">{warehouse.data ? `${warehouse.data.code} · ${warehouse.data.name}` : t('app.loading')}</h1>
          {scene.data && (
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              {t('view3d.summary', { racks: scene.data.racks.length, bins: scene.data.bins.ids.length })}
              <span aria-hidden className={`size-2 rounded-full ${live === 'live' ? 'bg-emerald-500' : live === 'connecting' ? 'bg-amber-400' : 'bg-slate-400'}`} />
              <span>{t(`view3d.live.${live}` as MessageKey)}</span>
            </p>
          )}
        </div>
        {scene.data && (
          <form role="search" className="relative" onSubmit={(e) => {
            e.preventDefault()
            find(query)
          }}>
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <input ref={searchBox} aria-label={t('view3d.search')} placeholder={t('view3d.searchHint')} value={query} list="bin-codes"
              maxLength={40} onChange={(e) => {
                setQuery(e.target.value)
                setNotFound(null)
              }}
              className="h-9 w-56 rounded-md border border-input bg-background pr-2 pl-8 font-mono text-sm uppercase placeholder:font-sans placeholder:normal-case" />
            <datalist id="bin-codes">{hints.map((c) => <option key={c} value={c} />)}</datalist>
          </form>
        )}
        {scene.data && (
          <div role="radiogroup" aria-label={t('view3d.viewMode')} className="flex rounded-md border p-0.5">
            {(['3d', 'lite'] as ViewMode[]).map((v) => (
              <button key={v} type="button" role="radio" aria-checked={view === v} onClick={() => setView(v)}
                className={`flex items-center gap-1 rounded px-2 py-1 text-sm font-medium ${view === v ? 'bg-primary text-primary-foreground' : 'hover:bg-secondary'}`}>
                {v === '3d' ? <Cuboid className="size-4" /> : <Square className="size-4" />} {t(`view3d.view.${v}` as MessageKey)}
              </button>
            ))}
          </div>
        )}
        {can(me.data, 'warehouses:manage') && (
          <Button asChild variant="outline" size="sm"><Link to={`/app/warehouses/${id}/layout`}><Map /> {t('view3d.editLayout')}</Link></Button>
        )}
      </div>
      <div className="relative min-h-0 flex-1">
        {notPublished && <p className="p-6 text-muted-foreground">{t('view3d.notPublished')}</p>}
        {scene.isError && !notPublished && <div className="p-6"><FormMessage tone="error">{errorMessage(scene.error)}</FormMessage></div>}
        {scene.data && view === 'lite' && (
          <LiteMap scene={scene.data} colors={colors} selectedBin={selected} selectedRack={rack} onRack={setRack} route={route} />
        )}
        {scene.data && view === 'lite' && rack !== null && (
          <RackBins scene={scene.data} rack={rack} colors={colors} selectedBin={selected} onBin={setSelected}
            onClose={() => setRack(null)} />
        )}
        {slow && view === '3d' && (
          <div role="alert" className="absolute top-3 left-1/2 z-10 flex -translate-x-1/2 items-center gap-3 rounded-lg border bg-card px-4 py-2 text-sm shadow-lg">
            {t('view3d.slow')}
            <Button size="sm" onClick={() => setView('lite')}>{t('view3d.slowSwitch')}</Button>
            <Button size="sm" variant="ghost" onClick={() => setSlow(false)}>{t('view3d.slowKeep')}</Button>
          </div>
        )}
        {scene.data && view === '3d' && (
          <Suspense fallback={<p className="p-6 text-sm text-muted-foreground">{t('view3d.loading3d')}</p>}>
            {parts && <SceneCanvas scene={scene.data} parts={parts} colors={colors} selected={selected} onSelect={setSelected} onCamera={onCamera} onSlow={onSlow} route={route} />}
          </Suspense>
        )}
        {/* Bottom bar: legend left and camera right; stacked on phones (camera above) so nothing overlaps. */}
        {scene.data && (
          <div className="pointer-events-none absolute inset-x-3 bottom-3 flex flex-col-reverse gap-2 sm:flex-row sm:items-end sm:justify-between">
            <div className="pointer-events-auto space-y-2 rounded-lg border bg-card/90 p-2 text-xs shadow-sm sm:max-w-[60%]">
              <div role="radiogroup" aria-label={t('view3d.colorBy')} className="flex gap-1">
                {(['zone', 'status', 'expiry', 'heat'] as ColorMode[]).map((m) => (
                  <button key={m} type="button" role="radio" aria-checked={mode === m} onClick={() => setMode(m)}
                    className={`rounded px-2 py-1 font-medium ${mode === m ? 'bg-primary text-primary-foreground' : 'hover:bg-secondary'}`}>
                    {t(`view3d.colorBy.${m}` as MessageKey)}
                  </button>
                ))}
              </div>
              <ul className="flex flex-wrap gap-x-3 gap-y-1">
                {legend(scene.data, mode).map((l) => (
                  <li key={l.key} className="flex items-center gap-1.5">
                    <span className="size-3 rounded-sm" style={{ background: l.color }} />
                    {mode === 'heat' ? (l.key === 'NONE' ? t('view3d.heat.none') : l.from === l.to ? (l.from === 1 ? t('view3d.heat.single') : t('view3d.heat.one', { n: l.from ?? 0 })) : t('view3d.heat.range', { from: l.from ?? 0, to: l.to ?? 0 }))
                      : mode === 'zone' ? t(`layout.zoneType.${l.key}` as MessageKey)
                      : mode === 'expiry' ? t(`view3d.expiry.${l.key}` as MessageKey) : t(`view3d.status.${l.key}` as MessageKey)}
                  </li>
                ))}
              </ul>
              {mode === 'status' && <p className="text-muted-foreground">{t('view3d.status.note')}</p>}
              {mode === 'expiry' && <p className="text-muted-foreground">{t('view3d.expiry.note')}</p>}
              {mode === 'heat' && <p className="text-muted-foreground">{t('view3d.heat.note')}</p>}
              {route && <p className="font-medium text-cyan-700 dark:text-cyan-300">{t('route.label', { n: route.stops.length, m: Math.round(route.metres) })}</p>}
            </div>
            {view === '3d' && (
              <div className="pointer-events-auto flex flex-col items-end gap-2 self-end">
                <div className="flex gap-1 rounded-lg border bg-card/90 p-1 shadow-sm">
                  <Button variant="ghost" size="icon" aria-label={t('view3d.zoomIn')} title={t('view3d.zoomIn')} onClick={() => camera.current?.zoom(1)}><ZoomIn /></Button>
                  <Button variant="ghost" size="icon" aria-label={t('view3d.zoomOut')} title={t('view3d.zoomOut')} onClick={() => camera.current?.zoom(-1)}><ZoomOut /></Button>
                  <Button variant="ghost" size="icon" aria-label={t('view3d.top')} title={t('view3d.top')} onClick={() => camera.current?.top()}><Scan /></Button>
                  <Button variant="ghost" size="icon" aria-label={t('view3d.reset')} title={t('view3d.reset')} onClick={() => camera.current?.reset()}><RotateCcw /></Button>
                </div>
                <p className="hidden rounded bg-card/80 px-2 py-1 text-xs text-muted-foreground sm:block">{t('view3d.keys')}</p>
              </div>
            )}
          </div>
        )}
        {notFound && (
          <p role="status" className="absolute top-3 left-3 rounded-md border bg-card/95 px-3 py-2 text-sm">{t('view3d.notFound', { code: notFound })}</p>
        )}
        {scene.data && selected !== null && (
          <BinPanel scene={scene.data} index={selected} warehouseId={id} canManage={can(me.data, 'warehouses:manage')}
            onClose={() => setSelected(null)} />
        )}
      </div>
    </div>
  )
}
