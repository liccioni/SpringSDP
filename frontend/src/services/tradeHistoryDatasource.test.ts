import { describe, expect, it, vi } from 'vitest'
import type { IGetRowsParams } from 'ag-grid-community'
import { TradeHistoryDatasource } from './tradeHistoryDatasource'
import type { Trade } from '../types/trade'

function fakeSend() {
  return vi.fn()
}

function fakeParams(overrides: Partial<IGetRowsParams> = {}): IGetRowsParams {
  return {
    startRow: 0,
    endRow: 100,
    successCallback: vi.fn(),
    failCallback: vi.fn(),
    sortModel: [],
    filterModel: {},
    context: undefined,
    ...overrides,
  } as unknown as IGetRowsParams
}

function fakeTrade(id: string): Trade {
  return { id, symbol: 'EUR/USD', side: 'BUY', price: 1.085, quantity: 1_000_000, timestamp: '2026-08-21T00:00:00Z' }
}

function sentMessage(sendFn: ReturnType<typeof fakeSend>, callIndex = 0) {
  return sendFn.mock.calls[callIndex][0]
}

describe('TradeHistoryDatasource', () => {
  it('sends a GET_TRADE_HISTORY request shaped from the getRows params', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(fakeParams({ startRow: 0, endRow: 100 }))

    const message = sentMessage(sendFn)
    expect(message.type).toBe('GET_TRADE_HISTORY')
    expect(typeof message.correlationId).toBe('string')
    expect(message.correlationId.length).toBeGreaterThan(0)
    expect(message.payload).toEqual({ pageSize: 100, cursor: null, sort: null, filters: null })
  })

  it('maps a single sort model entry to a TradeSort', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(fakeParams({ sortModel: [{ colId: 'symbol', sort: 'desc' }] }))

    expect(sentMessage(sendFn).payload.sort).toEqual({ column: 'symbol', descending: true })
  })

  it('maps a text and a number filter model to TradeFilters', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(
      fakeParams({
        filterModel: {
          symbol: { filterType: 'text', type: 'contains', filter: 'EUR' },
          quantity: { filterType: 'number', type: 'greaterThan', filter: 100000 },
        },
      }),
    )

    expect(sentMessage(sendFn).payload.filters).toEqual([
      { column: 'symbol', type: 'contains', value: 'EUR', valueTo: null },
      { column: 'quantity', type: 'greaterThan', value: '100000', valueTo: null },
    ])
  })

  it('maps a day-only "equals" date filter to an inRange TradeFilter covering the whole local day', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(
      fakeParams({
        filterModel: { timestamp: { filterType: 'date', type: 'equals', dateFrom: '2026-08-21', dateTo: null } },
      }),
    )

    const [filter] = sentMessage(sendFn).payload.filters
    expect(filter.column).toBe('timestamp')
    expect(filter.type).toBe('inRange')
    expect(new Date(filter.value)).toEqual(new Date(2026, 7, 21, 0, 0, 0))
    expect(new Date(filter.valueTo)).toEqual(new Date(2026, 7, 22, 0, 0, 0, -1))
  })

  it('maps a "lessThan" date filter to a lessThan TradeFilter at the start of that day', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(
      fakeParams({
        filterModel: { timestamp: { filterType: 'date', type: 'lessThan', dateFrom: '2026-08-21', dateTo: null } },
      }),
    )

    const [filter] = sentMessage(sendFn).payload.filters
    expect(filter.type).toBe('lessThan')
    expect(filter.valueTo).toBeNull()
    expect(new Date(filter.value)).toEqual(new Date(2026, 7, 21, 0, 0, 0))
  })

  it('maps a "greaterThan" date filter to a greaterThan TradeFilter at the start of the next day', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(
      fakeParams({
        filterModel: { timestamp: { filterType: 'date', type: 'greaterThan', dateFrom: '2026-08-21', dateTo: null } },
      }),
    )

    const [filter] = sentMessage(sendFn).payload.filters
    expect(filter.type).toBe('greaterThan')
    expect(filter.valueTo).toBeNull()
    expect(new Date(filter.value)).toEqual(new Date(2026, 7, 22, 0, 0, 0, -1))
  })

  it('maps an "inRange" date filter spanning dateFrom through the end of dateTo', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(
      fakeParams({
        filterModel: {
          timestamp: { filterType: 'date', type: 'inRange', dateFrom: '2026-08-21', dateTo: '2026-08-23' },
        },
      }),
    )

    const [filter] = sentMessage(sendFn).payload.filters
    expect(filter.type).toBe('inRange')
    expect(new Date(filter.value)).toEqual(new Date(2026, 7, 21, 0, 0, 0))
    expect(new Date(filter.valueTo)).toEqual(new Date(2026, 7, 24, 0, 0, 0, -1))
  })

  it('resolves successCallback with the reply matching its correlationId', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)
    const params = fakeParams({ startRow: 0, endRow: 100 })

    datasource.getRows(params)
    const { correlationId } = sentMessage(sendFn)
    const rows = [fakeTrade('trade-1')]

    datasource.handleReply(correlationId, { rows, nextCursor: null, hasMore: false })

    expect(params.successCallback).toHaveBeenCalledWith(rows, 1)
  })

  it('reports an open-ended block via successCallback when hasMore is true', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)
    const params = fakeParams({ startRow: 0, endRow: 100 })

    datasource.getRows(params)
    const { correlationId } = sentMessage(sendFn)

    datasource.handleReply(correlationId, { rows: [fakeTrade('trade-1')], nextCursor: 'cursor-1', hasMore: true })

    expect(params.successCallback).toHaveBeenCalledWith([fakeTrade('trade-1')], undefined)
  })

  it('ignores a reply for a correlationId it never issued', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)
    const params = fakeParams()
    datasource.getRows(params)

    expect(() => datasource.handleReply('unknown-id', { rows: [], nextCursor: null, hasMore: false })).not.toThrow()
    expect(params.successCallback).not.toHaveBeenCalled()
  })

  it('resolves two overlapping getRows() calls to their own caller via distinct correlationIds', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)
    const paramsA = fakeParams({ startRow: 0, endRow: 100 })
    const paramsB = fakeParams({ startRow: 100, endRow: 200 })

    datasource.getRows(paramsA)
    datasource.getRows(paramsB)
    const correlationIdA = sentMessage(sendFn, 0).correlationId
    const correlationIdB = sentMessage(sendFn, 1).correlationId
    expect(correlationIdA).not.toBe(correlationIdB)

    const rowsB = [fakeTrade('trade-b')]
    const rowsA = [fakeTrade('trade-a')]
    datasource.handleReply(correlationIdB, { rows: rowsB, nextCursor: null, hasMore: false })
    datasource.handleReply(correlationIdA, { rows: rowsA, nextCursor: null, hasMore: false })

    expect(paramsA.successCallback).toHaveBeenCalledWith(rowsA, 1)
    expect(paramsB.successCallback).toHaveBeenCalledWith(rowsB, 101)
  })

  it('fails the request if no reply arrives before the timeout', () => {
    vi.useFakeTimers()
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)
    const params = fakeParams()

    datasource.getRows(params)
    vi.advanceTimersByTime(10_000)

    expect(params.failCallback).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('restarts from cursor=null when asked for a block whose preceding cursor was never observed', () => {
    const sendFn = fakeSend()
    const datasource = new TradeHistoryDatasource(sendFn)

    datasource.getRows(fakeParams({ startRow: 500, endRow: 600 }))

    expect(sentMessage(sendFn).payload.cursor).toBeNull()
  })
})
