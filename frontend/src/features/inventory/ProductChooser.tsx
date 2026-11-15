import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Field, Input } from '@/components/ui/input'
import { useProducts, type Product } from '@/features/catalog/api'
import { t } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'

/** Pick one product by SKU, barcode or name; once picked it shows with a Change button. */
export function ProductChooser({ value, onChange }: { value: Product | null; onChange: (p: Product | null) => void }) {
  const [text, setText] = useState('')
  const q = useDebounced(text.trim())
  const found = useProducts(q, 0)
  const matches = q && !found.isPlaceholderData ? (found.data?.items ?? []).slice(0, 6) : []
  if (value) {
    return (
      <div className="flex items-center gap-2 rounded-md border bg-card px-3 py-2">
        <span className="min-w-0 flex-1 truncate"><span className="font-mono text-sm font-semibold">{value.sku}</span> {value.name}</span>
        <Button type="button" variant="ghost" size="sm" onClick={() => onChange(null)}>{t('stock.changeProduct')}</Button>
      </div>
    )
  }
  return (
    <div className="grid gap-1">
      <Field label={t('stock.product')}>
        {(p) => <Input {...p} className="h-12" autoComplete="off" placeholder={t('stock.productHint')} value={text} maxLength={64}
          onChange={(e) => setText(e.target.value)} />}
      </Field>
      {matches.length > 0 && (
        <ul className="rounded-md border bg-card text-sm shadow-sm">
          {matches.map((p) => (
            <li key={p.id}>
              <button type="button" className="w-full px-3 py-2 text-left hover:bg-secondary" onClick={() => {
                onChange(p)
                setText('')
              }}>
                <span className="font-mono text-xs">{p.sku}</span> {p.name}
              </button>
            </li>
          ))}
        </ul>
      )}
      {q && found.data && !found.isPlaceholderData && matches.length === 0 && (
        <p className="text-xs text-muted-foreground">{t('po.noProduct')}</p>
      )}
    </div>
  )
}
