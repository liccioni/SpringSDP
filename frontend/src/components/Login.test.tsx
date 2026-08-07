import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Login from './Login'

describe('Login', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('calls onLogin with the token on successful login', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ token: 'abc-123' }),
      }),
    )
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('Username'), 'trader1')
    await userEvent.type(screen.getByLabelText('Password'), 'trader1pass')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(onLogin).toHaveBeenCalledWith('abc-123')
  })

  it('shows an error and does not call onLogin for invalid credentials', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('Username'), 'trader1')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('shows an error when the backend is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network error')))
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('Username'), 'trader1')
    await userEvent.type(screen.getByLabelText('Password'), 'trader1pass')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the backend')
    expect(onLogin).not.toHaveBeenCalled()
  })
})
