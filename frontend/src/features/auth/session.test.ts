import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import { errorMessage, fieldErrors, label } from '@/lib/errors'
import { can, type Me } from './session'

const me: Me = { id: 'U1', email: 'a@b.lk', fullName: 'A', roles: ['PICKER'], permissions: ['picking:execute'] }

describe('can', () => {
  it('checks the permission list', () => {
    expect(can(me, 'picking:execute')).toBe(true)
    expect(can(me, 'users:manage')).toBe(false)
    expect(can(undefined, 'picking:execute')).toBe(false)
  })
})

describe('errorMessage', () => {
  it('uses the text for known codes and the reference for unknown ones', () => {
    expect(errorMessage(new ApiError(409, 'LAST_ADMIN', 'x'))).toMatch(/at least one active/)
    expect(errorMessage(new ApiError(500, 'INTERNAL_ERROR', 'x', '01ABC'))).toContain('01ABC')
    expect(errorMessage(new Error('boom'))).toContain('Something went wrong')
  })

  it('maps validation errors by field', () => {
    const e = new ApiError(400, 'VALIDATION_FAILED', 'x', undefined, [{ field: 'email', message: 'bad' }])
    expect(fieldErrors(e)).toEqual({ email: 'bad' })
  })
})

describe('label', () => {
  it('uses en.json text, or makes the code readable', () => {
    expect(label('action', 'USER_DISABLED')).toBe('Disabled user')
    expect(label('action', 'SOMETHING_NEW')).toBe('Something new')
  })
})
