import { Download, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { FormMessage } from '@/components/ui/input'
import { Table, Th } from '@/components/ui/table'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useImport } from './api'

/**
 * CSV import (products or suppliers): download the template, pick a file, then either everything is saved or
 * nothing is and the rows to fix are listed. The server checks the file (SEC-46); this only shows the outcome.
 */
export function ImportDialog({ what, onClose }: { what: 'products' | 'suppliers'; onClose: () => void }) {
  const upload = useImport(what)
  const result = upload.data
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t(`import.title.${what}` as MessageKey)}
      description={t(`import.hint.${what}` as MessageKey)}>
      <div className="grid max-h-[70vh] gap-4 overflow-y-auto">
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline" size="sm">
            <a href={`/api/v1/${what}/import-template.csv`} download><Download /> {t('import.template')}</a>
          </Button>
          <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90">
            <Upload className="size-4" /> {upload.isPending ? t('import.working') : t('import.choose')}
            <input type="file" accept=".csv,text/csv" className="sr-only" disabled={upload.isPending}
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) upload.mutate(file)
                e.target.value = ''
              }} />
          </label>
        </div>
        <p className="text-xs text-muted-foreground">{t('import.rules')}</p>
        {upload.isError && <FormMessage tone="error">{errorMessage(upload.error)}</FormMessage>}
        {result?.saved && (
          <FormMessage tone="success">{t('import.done', { created: result.created, updated: result.updated })}</FormMessage>
        )}
        {result && !result.saved && (
          <>
            <FormMessage tone="error">{t('import.failed', { n: result.errors.length })}</FormMessage>
            <Table>
              <thead>
                <tr><Th>{t('import.col.row')}</Th><Th>{t('import.col.column')}</Th><Th>{t('import.col.problem')}</Th></tr>
              </thead>
              <tbody>
                {result.errors.map((e, i) => (
                  <tr key={i} className="border-t">
                    <td className="px-3 py-1.5 tabular-nums">{e.row}</td>
                    <td className="px-3 py-1.5 font-mono text-xs">{e.field}</td>
                    <td className="px-3 py-1.5 text-sm">{e.message}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </>
        )}
        <div className="flex justify-end">
          <Button variant="outline" onClick={onClose}>{t('import.close')}</Button>
        </div>
      </div>
    </Dialog>
  )
}
