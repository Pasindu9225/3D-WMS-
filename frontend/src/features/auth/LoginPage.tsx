import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { PageShell } from '@/app/PageShell'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useLogin, useMe } from './session'

// Zod is for quick feedback only; the server validates again (SEC-20).
const schema = z.object({
  email: z.string().trim().min(1, t('common.required')).max(190, t('common.tooLong')),
  password: z.string().min(1, t('common.required')).max(128, t('common.tooLong')),
})
type Values = z.infer<typeof schema>

export function LoginPage() {
  const me = useMe()
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '', password: '' } })
  const from = (location.state as { from?: string } | null)?.from ?? '/app'

  if (me.isSuccess) return <Navigate to={from} replace />

  const onSubmit = form.handleSubmit((values) =>
    login.mutate(values, { onSuccess: () => navigate(from, { replace: true }) }),
  )
  const { errors } = form.formState

  return (
    <PageShell>
      <form onSubmit={onSubmit} noValidate className="grid w-full max-w-sm gap-5 rounded-lg border bg-card p-6 shadow-sm">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold">{t('login.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('login.subtitle')}</p>
        </div>
        {params.get('expired') && !login.isError && <FormMessage tone="error">{t('login.expired')}</FormMessage>}
        {params.get('welcome') && !login.isError && <FormMessage tone="success">{t('login.passwordSet')}</FormMessage>}
        {login.isError && <FormMessage tone="error">{errorMessage(login.error)}</FormMessage>}
        <Field label={t('login.email')} error={errors.email?.message}>
          {(p) => <Input {...p} type="email" autoComplete="username" autoFocus {...form.register('email')} />}
        </Field>
        <Field label={t('login.password')} error={errors.password?.message}>
          {(p) => <Input {...p} type="password" autoComplete="current-password" {...form.register('password')} />}
        </Field>
        <Button type="submit" size="lg" disabled={login.isPending}>
          {login.isPending ? t('login.submitting') : t('login.submit')}
        </Button>
        <p className="text-center text-sm text-muted-foreground">{t('login.newHere')} <Link className="font-medium text-foreground underline" to="/signup">{t('login.createCompany')}</Link></p>
      </form>
    </PageShell>
  )
}
