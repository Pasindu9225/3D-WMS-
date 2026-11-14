import { ArrowLeft, Pencil, Printer } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { formatKg, parseWeight } from '@/features/layout/units'
import { BIN_PAGE_SIZE, labelsUrl, useBins, useEditBin, useWarehouse, type Bin } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'

/** Published bins of one warehouse: find by code, set pick faces and blocks, fix capacity, print labels. */
export function BinsPage() {
  const { id = '' } = useParams()
  const me = useMe()
  const manage = can(me.data, 'warehouses:manage')
  const print = can(me.data, 'labels:print')
  const warehouse = useWarehouse(id)
  const [search, setSearch] = useState('')
  const code = useDebounced(search.trim().toUpperCase())
  const [page, setPage] = useState(0)
  const bins = useBins(id, code, page)
  const edit = useEditBin(id)
  const [editing, setEditing] = useState<Bin | null>(null)
  const data = bins.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button asChild variant="ghost" size="icon" aria-label={t('layout.back')}>
          <Link to="/app/warehouses"><ArrowLeft /></Link>
        </Button>
        <div className="mr-auto">
          <h1 className="text-2xl font-semibold">{t('bins.title')}</h1>
          <p className="text-sm text-muted-foreground">{warehouse.data ? `${warehouse.data.code} · ${warehouse.data.name}` : t('app.loading')}</p>
        </div>
        {print && data && data.total > 0 && ( // no bins: the server answers with an error, not a PDF
          <>
            <Button asChild variant="outline"><a href={labelsUrl(id, code, 'sheet')} download><Printer /> {t('bins.labelsSheet')}</a></Button>
            <Button asChild variant="outline"><a href={labelsUrl(id, code, 'single')} download><Printer /> {t('bins.labelsSingle')}</a></Button>
          </>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Input aria-label={t('bins.search')} placeholder={t('bins.search')} value={search} maxLength={40} className="max-w-xs font-mono uppercase"
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }} />
        {print && <p className="text-xs text-muted-foreground">{code ? t('bins.labelsFor', { code }) : t('bins.labelsAll')}</p>}
      </div>
      {bins.isError && <FormMessage tone="error">{errorMessage(bins.error)}</FormMessage>}
      {edit.isError && <FormMessage tone="error">{errorMessage(edit.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('bins.col.code')}</Th>
            <Th>{t('bins.col.weight')}</Th>
            <Th className="hidden sm:table-cell">{t('bins.col.volume')}</Th>
            <Th>{t('bins.col.pickFace')}</Th>
            <Th>{t('bins.col.blocked')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((b) => (
            <Tr key={b.id}>
              <td className="font-mono font-medium">{b.code}</td>
              <td className="tabular-nums">
                {formatKg(b.maxWeightG)} {b.customCapacity && <Badge tone="warn">{t('bins.custom')}</Badge>}
              </td>
              <td className="hidden tabular-nums sm:table-cell">{t('bins.volume', { m3: Math.round(b.maxVolumeCm3 / 10_000) / 100 })}</td>
              <td>
                <input type="checkbox" aria-label={t('bins.pickFaceFor', { code: b.code })} checked={b.pickFace} disabled={!manage || edit.isPending}
                  onChange={(e) => edit.mutate({ id: b.id, pickFace: e.target.checked })} className="size-4" />
              </td>
              <td>
                <input type="checkbox" aria-label={t('bins.blockedFor', { code: b.code })} checked={b.blocked} disabled={!manage || edit.isPending}
                  onChange={(e) => edit.mutate({ id: b.id, blocked: e.target.checked })} className="size-4" />
              </td>
              <td className="text-right">
                {manage && (
                  <Button variant="ghost" size="icon" aria-label={t('bins.editFor', { code: b.code })} onClick={() => setEditing(b)}><Pencil /></Button>
                )}
              </td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{code ? t('bins.noMatch') : t('bins.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {data && data.total > BIN_PAGE_SIZE && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'),
            next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}
      {editing && <CapacityDialog bin={editing} warehouseId={id} onClose={() => setEditing(null)} />}
    </div>
  )
}

function CapacityDialog({ bin, warehouseId, onClose }: { bin: Bin; warehouseId: string; onClose: () => void }) {
  const edit = useEditBin(warehouseId)
  const [weight, setWeight] = useState(String(bin.maxWeightG / 1000))
  const [volume, setVolume] = useState(String(Math.round(bin.maxVolumeCm3 / 10_000) / 100))
  const g = parseWeight(weight)
  const m3 = Number(volume)
  const cm3 = Number.isFinite(m3) && volume.trim() !== '' ? Math.round(m3 * 1_000_000) : null
  const valid = g !== null && g >= 1 && g <= 100_000_000 && cm3 !== null && cm3 >= 1 && cm3 <= 100_000_000
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('bins.editTitle', { code: bin.code })} description={t('bins.editHint')}>
      <form noValidate className="grid gap-4" onSubmit={(e) => {
        e.preventDefault()
        if (valid) edit.mutate({ id: bin.id, maxWeightG: g, maxVolumeCm3: cm3 }, { onSuccess: onClose })
      }}>
        {edit.isError && <FormMessage tone="error">{errorMessage(edit.error)}</FormMessage>}
        <div className="grid grid-cols-2 gap-3">
          <label className="grid gap-1.5 text-sm font-medium">{t('bins.weightField')}
            <Input inputMode="decimal" value={weight} onChange={(e) => setWeight(e.target.value)} aria-invalid={g === null || undefined} />
          </label>
          <label className="grid gap-1.5 text-sm font-medium">{t('bins.volumeField')}
            <Input inputMode="decimal" value={volume} onChange={(e) => setVolume(e.target.value)} aria-invalid={cm3 === null || undefined} />
          </label>
        </div>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || edit.isPending}>{edit.isPending ? t('common.saving') : t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
