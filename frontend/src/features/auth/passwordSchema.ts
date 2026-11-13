import { z } from 'zod'
import { t } from '@/lib/i18n'

/** Same limits as the server: 10 to 128 characters. */
export const newPasswordSchema = z
  .object({
    password: z.string().min(10, t('validation.passwordLength')).max(128, t('common.tooLong')),
    confirm: z.string(),
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: t('validation.passwordMatch') })

export type NewPassword = z.infer<typeof newPasswordSchema>
