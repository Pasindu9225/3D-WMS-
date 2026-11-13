import { Check } from 'lucide-react'
import { FormMessage } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { useRoles } from '@/features/users/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'

/** Read-only matrix: permissions down, roles across. Built-in roles cannot be edited (decided in task 6). */
export function RolesPage() {
  const roles = useRoles()
  const list = roles.data ?? []
  // Permission order follows the most complete role (Tenant admin has all), grouped by area prefix.
  const permissions = [...new Set(list.flatMap((r) => r.permissions))].sort()

  return (
    <div className="grid gap-4">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold">{t('roles.title')}</h1>
        <p className="max-w-2xl text-muted-foreground">{t('roles.body')}</p>
      </div>
      {roles.isError && <FormMessage tone="error">{errorMessage(roles.error)}</FormMessage>}
      {roles.isPending && <p className="text-sm text-muted-foreground">{t('app.loading')}</p>}
      {list.length > 0 && (
        <Table>
          <thead>
            <tr>
              <Th className="sticky left-0 z-10">{t('roles.permission')}</Th>
              {list.map((r) => (
                <Th key={r.code} className="text-center">{r.name}</Th>
              ))}
            </tr>
          </thead>
          <tbody>
            {permissions.map((p) => (
              <Tr key={p}>
                <td className="sticky left-0 bg-card font-mono text-xs whitespace-nowrap">{p}</td>
                {list.map((r) => (
                  <td key={r.code} className="text-center">
                    {r.permissions.includes(p) ? (
                      <Check className="mx-auto size-4 text-primary" aria-label={`${r.name}: ${p}`} />
                    ) : (
                      <span className="text-muted-foreground/40" aria-hidden>·</span>
                    )}
                  </td>
                ))}
              </Tr>
            ))}
          </tbody>
        </Table>
      )}
    </div>
  )
}
