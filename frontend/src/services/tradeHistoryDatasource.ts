import type { IDatasource, IGetRowsParams } from 'ag-grid-community'
import type { TradeFilter, TradeHistoryPage, TradeHistoryQuery, TradeSort } from '../types/tradeHistory'

const REQUEST_TIMEOUT_MS = 10_000

interface PendingRequest {
  startRow: number
  timeout: ReturnType<typeof setTimeout>
  successCallback: IGetRowsParams['successCallback']
  failCallback: IGetRowsParams['failCallback']
}

function toSort(sortModel: IGetRowsParams['sortModel']): TradeSort | null {
  const [first] = sortModel
  return first ? { column: first.colId, descending: first.sort === 'desc' } : null
}

// A day-only date filter ("today") has no meaningful equality against the
// backend's Instant-typed timestamp column - it needs a range covering the
// whole local calendar day instead. Built the same way TradeBlotter's old
// client-side comparator built "today" (local Date components, not UTC), so
// filtering by day means the same thing it always has for this grid.
function startOfLocalDay(dateOnly: string): Date {
  const [year, month, day] = dateOnly.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function endOfLocalDayInclusive(dateOnly: string): Date {
  const start = startOfLocalDay(dateOnly)
  return new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1, 0, 0, 0, -1)
}

function timestampFilter(column: string, model: DateFilterModelShape): TradeFilter {
  switch (model.type) {
    case 'lessThan':
      return { column, type: 'lessThan', value: startOfLocalDay(model.dateFrom).toISOString(), valueTo: null }
    case 'greaterThan':
      return {
        column,
        type: 'greaterThan',
        value: endOfLocalDayInclusive(model.dateFrom).toISOString(),
        valueTo: null,
      }
    case 'inRange':
      return {
        column,
        type: 'inRange',
        value: startOfLocalDay(model.dateFrom).toISOString(),
        valueTo: endOfLocalDayInclusive(model.dateTo ?? model.dateFrom).toISOString(),
      }
    default:
      // 'equals' (the only other option offered - see TradeBlotter's
      // agDateColumnFilter filterOptions) means "this calendar day".
      return {
        column,
        type: 'inRange',
        value: startOfLocalDay(model.dateFrom).toISOString(),
        valueTo: endOfLocalDayInclusive(model.dateFrom).toISOString(),
      }
  }
}

interface DateFilterModelShape {
  filterType: 'date'
  type: string
  dateFrom: string
  dateTo?: string | null
}

interface SimpleFilterModelShape {
  filterType: 'text' | 'number'
  type: string
  filter?: string | number | null
  filterTo?: string | number | null
}

function simpleFilter(column: string, model: SimpleFilterModelShape): TradeFilter {
  return {
    column,
    type: model.type,
    value: String(model.filter ?? ''),
    valueTo: model.filterTo != null ? String(model.filterTo) : null,
  }
}

function toFilters(filterModel: IGetRowsParams['filterModel']): TradeFilter[] | null {
  const columns = Object.keys(filterModel)
  if (columns.length === 0) {
    return null
  }
  return columns.map((column) => {
    const model = filterModel[column] as DateFilterModelShape | SimpleFilterModelShape
    return model.filterType === 'date' ? timestampFilter(column, model) : simpleFilter(column, model)
  })
}

// AG Grid's Infinite Row Model datasource for the trade blotter (issue #132).
// Generates a correlationId per getRows() call and tracks it in pendingByCorrelationId,
// mirroring the gateway's own request/reply correlation (issue #131) one layer
// up - socket.ts stays a generic thin transport with no knowledge of this.
//
// cursorByBlockStart caches, for each block's startRow, the cursor that
// produces it - built up as blocks are fetched in normal top-to-bottom scroll
// order. A request for a startRow whose cursor was never recorded (a hard
// scrollbar-drag jump) restarts from cursor=null rather than reconstructing
// an arbitrary offset - an accepted v1 limitation for a live trade blotter.
export class TradeHistoryDatasource implements IDatasource {
  private readonly socket: WebSocket
  private readonly pendingByCorrelationId = new Map<string, PendingRequest>()
  private readonly cursorByBlockStart = new Map<number, string | null>([[0, null]])

  constructor(socket: WebSocket) {
    this.socket = socket
  }

  getRows(params: IGetRowsParams): void {
    let cursor = this.cursorByBlockStart.get(params.startRow)
    if (cursor === undefined) {
      this.cursorByBlockStart.clear()
      this.cursorByBlockStart.set(0, null)
      cursor = null
    }

    const correlationId = crypto.randomUUID()
    const query: TradeHistoryQuery = {
      pageSize: params.endRow - params.startRow,
      cursor,
      sort: toSort(params.sortModel),
      filters: toFilters(params.filterModel),
    }

    this.pendingByCorrelationId.set(correlationId, {
      startRow: params.startRow,
      successCallback: params.successCallback,
      failCallback: params.failCallback,
      timeout: setTimeout(() => this.fail(correlationId), REQUEST_TIMEOUT_MS),
    })

    this.socket.send(JSON.stringify({ type: 'GET_TRADE_HISTORY', payload: query, correlationId }))
  }

  handleReply(correlationId: string, page: TradeHistoryPage): void {
    const request = this.takePending(correlationId)
    if (!request) {
      return
    }
    this.cursorByBlockStart.set(request.startRow + page.rows.length, page.nextCursor)
    request.successCallback(page.rows, page.hasMore ? undefined : request.startRow + page.rows.length)
  }

  private fail(correlationId: string): void {
    this.takePending(correlationId)?.failCallback()
  }

  private takePending(correlationId: string): PendingRequest | undefined {
    const request = this.pendingByCorrelationId.get(correlationId)
    if (!request) {
      return undefined
    }
    this.pendingByCorrelationId.delete(correlationId)
    clearTimeout(request.timeout)
    return request
  }
}
