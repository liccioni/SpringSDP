import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import { connect, logout } from './socket'

const WS_URL = 'ws://localhost:8080/ws'

describe('connect', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('parses incoming envelope messages and invokes the callback', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'HELLO', payload: 'hi' }))
    })

    const received = await new Promise((resolve) => {
      const socket = connect((envelope) => {
        resolve(envelope)
        socket.close()
      })
    })

    expect(received).toEqual({ type: 'HELLO', payload: 'hi' })
  })

  it('invokes onOpen once the connection is established', async () => {
    const opened = await new Promise((resolve) => {
      const socket = connect(() => {}, () => {
        resolve(true)
        socket.close()
      })
    })

    expect(opened).toBe(true)
  })

  // The "redirect to Keycloak login if the connection closes without ever
  // opening" behavior isn't covered here: jsdom's window.location is
  // unforgeable (neither reassigning it nor spying on its href setter is
  // respected, even via Object.defineProperty), a well-documented jsdom
  // limitation rather than something specific to this code. Live-verified
  // instead - see the PR description.
})

describe('logout', () => {
  let submit: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=csrf-token-value'
    // jsdom has no real navigation, so HTMLFormElement.prototype.submit logs
    // a "not implemented" error unless stubbed - this also lets the test
    // assert the form was actually submitted, not just built.
    submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
  })

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
    document.querySelectorAll('form').forEach((form) => form.remove())
    submit.mockRestore()
  })

  it('submits a hidden POST form carrying the CSRF cookie value as a form field', () => {
    logout()

    const form = document.querySelector('form')
    expect(form).not.toBeNull()
    expect(form?.method).toBe('post')
    expect(form?.action).toBe('http://localhost:8080/logout')

    const csrfInput = form?.querySelector<HTMLInputElement>('input[name="_csrf"]')
    expect(csrfInput?.type).toBe('hidden')
    expect(csrfInput?.value).toBe('csrf-token-value')

    expect(submit).toHaveBeenCalledOnce()
  })
})
