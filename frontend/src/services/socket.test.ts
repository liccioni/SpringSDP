import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import { logout, resetForTests, send, subscribe } from './socket'
import type { Envelope } from '../types/envelope'

const WS_URL = 'ws://localhost:8080/ws'

describe('subscribe/send', () => {
  let mockServer: Server

  beforeEach(() => {
    resetForTests()
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('invokes the subscribed handler for a matching envelope type', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'HELLO', payload: 'hi' }))
    })

    const received = await new Promise<Envelope>((resolve) => {
      subscribe('HELLO', (envelope: Envelope) => resolve(envelope))
    })

    expect(received).toEqual({ type: 'HELLO', payload: 'hi' })
  })

  it('dispatches an incoming envelope to every subscriber registered for its type', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'TRADE_CREATED', payload: 'trade-a' }))
    })

    const handlerA = vi.fn()
    const handlerB = vi.fn()
    subscribe('TRADE_CREATED', handlerA)
    subscribe('TRADE_CREATED', handlerB)

    await waitFor(() => expect(handlerA).toHaveBeenCalledTimes(1))
    expect(handlerB).toHaveBeenCalledTimes(1)
    expect(handlerA).toHaveBeenCalledWith({ type: 'TRADE_CREATED', payload: 'trade-a' })
    expect(handlerB).toHaveBeenCalledWith({ type: 'TRADE_CREATED', payload: 'trade-a' })
  })

  it('does not invoke a handler subscribed to a different envelope type', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'PRICE_TICK', payload: 'tick' }))
    })

    const handler = vi.fn()
    subscribe('TRADE_REJECTED', handler)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(handler).not.toHaveBeenCalled()
  })

  it('stops invoking a handler once its subscription has been unsubscribed', async () => {
    let serverSocket: Parameters<Parameters<Server['on']>[1]>[0] | undefined
    mockServer.on('connection', (socket) => {
      serverSocket = socket
    })

    const handler = vi.fn()
    const unsubscribe = subscribe('TRADE_CANCELLED', handler)
    await waitFor(() => expect(serverSocket).toBeDefined())

    unsubscribe()
    serverSocket!.send(JSON.stringify({ type: 'TRADE_CANCELLED', payload: 'x' }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(handler).not.toHaveBeenCalled()
  })

  it('queues a send made before the connection opens and flushes it, in order, once open', async () => {
    const received: string[] = []
    mockServer.on('connection', (socket) => {
      socket.on('message', (message) => received.push(message as string))
    })

    send({ type: 'SUBSCRIBE', payload: { symbol: 'EUR/USD' } })
    send({ type: 'SUBSCRIBE', payload: { symbol: 'GBP/USD' } })

    await waitFor(() => expect(received).toHaveLength(2))
    expect(received.map((message) => JSON.parse(message))).toEqual([
      { type: 'SUBSCRIBE', payload: { symbol: 'EUR/USD' } },
      { type: 'SUBSCRIBE', payload: { symbol: 'GBP/USD' } },
    ])
  })

  // The "redirect to Keycloak login once every retry attempt has failed"
  // behavior isn't covered here: jsdom's window.location is unforgeable
  // (neither reassigning it nor spying on its href setter is respected, even
  // via Object.defineProperty), a well-documented jsdom limitation rather
  // than something specific to this code. Live-verified instead - see the PR
  // description. The retry mechanics themselves (below) are testable without
  // ever reaching that final redirect.
})

describe('connection retry', () => {
  // A small hand-rolled fake rather than mock-socket: this describe block
  // needs precise, synchronous control over exactly when a connection
  // "closes without ever opening" - timing mock-socket's own internal
  // open/close sequencing to land there deterministically isn't practical.
  class ControllableSocket extends EventTarget {
    static instances: ControllableSocket[] = []
    url: string

    constructor(url: string) {
      super()
      this.url = url
      ControllableSocket.instances.push(this)
    }

    send(): void {
      // Not exercised by this describe block.
    }

    close(): void {
      // resetForTests() calls this between tests; no event needed - the
      // module already nulls its own reference synchronously.
    }

    triggerOpen(): void {
      this.dispatchEvent(new Event('open'))
    }

    triggerClose(): void {
      this.dispatchEvent(new Event('close'))
    }
  }

  beforeEach(() => {
    resetForTests()
    ControllableSocket.instances = []
    vi.stubGlobal('WebSocket', ControllableSocket)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('retries opening the connection after a close-before-open, resetting the attempt count once open succeeds', async () => {
    subscribe('HELLO', () => {})

    await waitFor(() => expect(ControllableSocket.instances).toHaveLength(1))
    ControllableSocket.instances[0].triggerClose()

    await waitFor(() => expect(ControllableSocket.instances).toHaveLength(2), { timeout: 1000 })
    ControllableSocket.instances[1].triggerClose()

    await waitFor(() => expect(ControllableSocket.instances).toHaveLength(3), { timeout: 2000 })
    ControllableSocket.instances[2].triggerOpen()

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(ControllableSocket.instances).toHaveLength(3)
  })
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
