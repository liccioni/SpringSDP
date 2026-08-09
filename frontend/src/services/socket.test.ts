import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import { connect } from './socket'

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
