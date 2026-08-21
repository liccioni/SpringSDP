import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import TradeBlotter from './TradeBlotter'
import type { Trade } from '../types/trade'
import type { TradeHistoryPage } from '../types/tradeHistory'

const WS_URL = 'ws://localhost:8080/ws'

interface TradeHistoryRequest {
  type: 'GET_TRADE_HISTORY'
  payload: { pageSize: number; cursor: string | null; sort: unknown; filters: unknown[] | null }
  correlationId: string
}

function fakeTrade(overrides: Partial<Trade> = {}): Trade {
  return {
    id: 'trade-1',
    symbol: 'EUR/USD',
    side: 'BUY',
    price: 1.0852,
    quantity: 1000000,
    timestamp: '2026-08-21T10:00:00.000Z',
    ...overrides,
  }
}

function tradeHistoryReply(correlationId: string, page: TradeHistoryPage) {
  return JSON.stringify({ type: 'TRADE_HISTORY', correlationId, payload: page })
}

// Answers every GET_TRADE_HISTORY the blotter sends with the given page
// (or the last one, if there are more requests than pages), recording each
// request so tests can assert on how many were made and what they asked for.
function respondToTradeHistoryRequests(mockServer: Server, pages: TradeHistoryPage[]): TradeHistoryRequest[] {
  const requests: TradeHistoryRequest[] = []
  mockServer.on('connection', (socket) => {
    socket.on('message', (message) => {
      const request = JSON.parse(message as string) as TradeHistoryRequest
      if (request.type !== 'GET_TRADE_HISTORY') {
        return
      }
      requests.push(request)
      const page = pages[Math.min(requests.length - 1, pages.length - 1)]
      socket.send(tradeHistoryReply(request.correlationId, page))
    })
  })
  return requests
}

describe('TradeBlotter', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
    // jsdom never runs real layout, so the Infinite Row Model's viewport-size
    // calculation (how many rows fit in .panel__grid--fixed-height) always
    // reads a zero clientHeight/offsetHeight and renders no rows at all.
    // Stubbing a plausible viewport size is the standard AG Grid + jsdom
    // workaround - unlike the old domLayout="autoHeight" grid, which sized
    // to its content and never needed this.
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(800)
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(1000)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(800)
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1000)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('requests the first block of trade history once the connection opens', async () => {
    const requests = respondToTradeHistoryRequests(mockServer, [{ rows: [], nextCursor: null, hasMore: false }])

    render(<TradeBlotter />)

    await waitFor(() => expect(requests.length).toBeGreaterThan(0))
    const [request] = requests
    expect(request.payload).toMatchObject({ pageSize: 100, cursor: null, sort: null })
    expect(request.correlationId).toBeTruthy()
  })

  it('displays rows from a TRADE_HISTORY reply', async () => {
    respondToTradeHistoryRequests(mockServer, [
      { rows: [fakeTrade({ id: 'trade-1', symbol: 'AUD/USD' })], nextCursor: null, hasMore: false },
    ])

    render(<TradeBlotter />)

    expect(await screen.findByText('AUD/USD')).toBeInTheDocument()
  })

  it('renders trade history rows in the order the server returns them', async () => {
    respondToTradeHistoryRequests(mockServer, [
      {
        rows: [
          fakeTrade({ id: 'trade-newest', symbol: 'USD/JPY' }),
          fakeTrade({ id: 'trade-oldest', symbol: 'GBP/USD' }),
        ],
        nextCursor: null,
        hasMore: false,
      },
    ])

    const { container } = render(<TradeBlotter />)

    await screen.findByText('GBP/USD')
    const symbolCells = Array.from(
      container.querySelectorAll('[col-id="symbol"]:not([role="presentation"]):not(.ag-floating-filter)'),
    )
    expect(symbolCells.map((cell) => cell.textContent?.trim())).toEqual(['Symbol', 'USD/JPY', 'GBP/USD'])
  })

  it('ignores envelopes that are neither TRADE_CREATED nor TRADE_HISTORY', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'PRICE_TICK', payload: { symbol: 'NZD/USD', bid: 0.61, ask: 0.6102 } }))
    })

    render(<TradeBlotter />)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText('NZD/USD')).not.toBeInTheDocument()
  })

  it('refetches trade history once, after a debounce window, for a burst of TRADE_CREATED events', async () => {
    let serverSocket: Parameters<Parameters<Server['on']>[1]>[0] | undefined
    const requests = respondToTradeHistoryRequests(mockServer, [{ rows: [], nextCursor: null, hasMore: false }])
    mockServer.on('connection', (socket) => {
      serverSocket = socket
    })

    render(<TradeBlotter />)
    await waitFor(() => expect(requests).toHaveLength(1))

    serverSocket?.send(JSON.stringify({ type: 'TRADE_CREATED', payload: fakeTrade({ id: 'trade-2' }) }))
    serverSocket?.send(JSON.stringify({ type: 'TRADE_CREATED', payload: fakeTrade({ id: 'trade-3' }) }))

    await new Promise((resolve) => setTimeout(resolve, 350))
    await waitFor(() => expect(requests).toHaveLength(2))
  })
})
