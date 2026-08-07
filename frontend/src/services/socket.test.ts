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
      const socket = connect(
        (envelope) => {
          resolve(envelope)
          socket.close()
        },
        'test-token',
      )
    })

    expect(received).toEqual({ type: 'HELLO', payload: 'hi' })
  })

  it('invokes onOpen once the connection is established', async () => {
    const opened = await new Promise((resolve) => {
      const socket = connect(
        () => {},
        'test-token',
        () => {
          resolve(true)
          socket.close()
        },
      )
    })

    expect(opened).toBe(true)
  })

  it('appends the token as a query parameter on the connection URL', async () => {
    let connectedUrl: string | undefined
    mockServer.on('connection', (socket) => {
      connectedUrl = socket.url
    })

    await new Promise<void>((resolve) => {
      const socket = connect(() => {}, 'abc-123', () => {
        resolve()
        socket.close()
      })
    })

    expect(connectedUrl).toContain('token=abc-123')
  })
})
