import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import {
  AllCommunityModule,
  ModuleRegistry,
  type ColDef,
  type GetRowIdParams,
  type GridApi,
  type GridReadyEvent,
  type ICellRendererParams,
  type RowClassRules,
} from 'ag-grid-community'
import { connect } from '../services/socket'
import { tradingTheme } from '../theme/tradingTheme'
import { TradeHistoryDatasource } from '../services/tradeHistoryDatasource'
import { debounce } from '../utils/debounce'
import type { Trade } from '../types/trade'
import type { TradeHistoryPage } from '../types/tradeHistory'

ModuleRegistry.registerModules([AllCommunityModule])

// A live TRADE_CREATED burst refetches once per REFRESH_DEBOUNCE_MS window
// rather than once per event, to avoid hammering trading-service.
const REFRESH_DEBOUNCE_MS = 300

// Matches the WS request's pageSize (issue #132) - tunable later.
const CACHE_BLOCK_SIZE = 100

function SideBadge({ value }: ICellRendererParams<Trade, Trade['side']>) {
  return <span className={`side-badge side-badge--${value?.toLowerCase()}`}>{value}</span>
}

// The Infinite Row Model renders placeholder "loading" rows - with an
// undefined value for every column - for any block position it hasn't
// fetched yet, so this must tolerate undefined rather than assume every
// rendered row already has real data.
function formatTimeOfDay(timestamp: string | undefined): string {
  return timestamp ? (timestamp.split('T')[1]?.slice(0, 8) ?? timestamp) : ''
}

// Trade.timestamp is an ISO-8601 string, not a Date - agDateColumnFilter
// needs a comparator to know how to weigh the filter's chosen calendar day
// against it (falls back to a plain text "contains" filter without one).
function compareTimestampToFilterDate(filterLocalDateAtMidnight: Date, cellValue: string | undefined): number {
  if (!cellValue) {
    return 0
  }
  const cellDate = new Date(cellValue)
  const cellDateAtMidnight = new Date(cellDate.getFullYear(), cellDate.getMonth(), cellDate.getDate())
  return cellDateAtMidnight.getTime() - filterLocalDateAtMidnight.getTime()
}

// AG Grid's date filter model wants 'yyyy-mm-dd' in local time - building it
// from getFullYear/getMonth/getDate (rather than toISOString, which is UTC)
// keeps "today" meaning the viewer's today, not UTC's.
function todaysDateFilterModel() {
  const now = new Date()
  const yyyy = now.getFullYear()
  const mm = String(now.getMonth() + 1).padStart(2, '0')
  const dd = String(now.getDate()).padStart(2, '0')
  return { filterType: 'date' as const, type: 'equals', dateFrom: `${yyyy}-${mm}-${dd}`, dateTo: null }
}

// Column filter options are restricted to what TradeHistoryQueryService's
// column allow-list actually supports (issue #130) - the grid's own default
// options (notEqual, endsWith, blank, ...) would otherwise let a user pick
// an operator that 500s on the backend.
const textFilterParams = { filterOptions: ['contains', 'equals', 'startsWith'], maxNumConditions: 1 }
const numberFilterParams = { filterOptions: ['equals', 'lessThan', 'greaterThan', 'inRange'], maxNumConditions: 1 }
const dateFilterParams = {
  filterOptions: ['equals', 'lessThan', 'greaterThan', 'inRange'],
  maxNumConditions: 1,
  comparator: compareTimestampToFilterDate,
}

const columnDefs: ColDef<Trade>[] = [
  {
    field: 'timestamp',
    headerName: 'Time',
    valueFormatter: (params) => formatTimeOfDay(params.value),
    filter: 'agDateColumnFilter',
    filterParams: dateFilterParams,
    flex: 0.8,
  },
  { field: 'symbol', headerName: 'Symbol', cellClass: 'cell-symbol', filterParams: textFilterParams, flex: 1 },
  { field: 'side', headerName: 'Side', cellRenderer: SideBadge, filterParams: textFilterParams, flex: 0.7 },
  { field: 'price', headerName: 'Price', type: 'rightAligned', filterParams: numberFilterParams, flex: 1 },
  { field: 'quantity', headerName: 'Quantity', type: 'rightAligned', filterParams: numberFilterParams, flex: 1.1 },
]

const defaultColDef: ColDef<Trade> = { filter: true, floatingFilter: true, sortable: true }

function getRowId(params: GetRowIdParams<Trade>) {
  return params.data.id
}

// Computed once, at module load - the blotter is mounted once for the
// lifetime of the app, so "today" here means whatever day the page loaded on.
const initialGridState = { filter: { filterModel: { timestamp: todaysDateFilterModel() } } }

function TradeBlotter() {
  const gridApiRef = useRef<GridApi<Trade> | null>(null)
  const datasourceRef = useRef<TradeHistoryDatasource | null>(null)
  const [latestTradeId, setLatestTradeId] = useState<string | null>(null)

  const refreshTrades = useMemo(
    () => debounce(() => gridApiRef.current?.refreshInfiniteCache(), REFRESH_DEBOUNCE_MS),
    [],
  )

  useEffect(() => {
    const socket = connect(
      (envelope) => {
        if (envelope.type === 'TRADE_HISTORY' && envelope.correlationId) {
          const page = envelope.payload as TradeHistoryPage
          datasourceRef.current?.handleReply(envelope.correlationId, page)
        } else if (envelope.type === 'TRADE_CREATED') {
          setLatestTradeId((envelope.payload as Trade).id)
          refreshTrades()
        }
      },
      () => {
        datasourceRef.current = new TradeHistoryDatasource(socket)
        gridApiRef.current?.setGridOption('datasource', datasourceRef.current)
      },
    )

    return () => socket.close()
  }, [refreshTrades])

  const onGridReady = useCallback((event: GridReadyEvent<Trade>) => {
    gridApiRef.current = event.api
    if (datasourceRef.current) {
      event.api.setGridOption('datasource', datasourceRef.current)
    }
  }, [])

  const rowClassRules = useMemo<RowClassRules<Trade>>(
    () => ({
      'trade-row--new': (params) => params.data?.id === latestTradeId,
    }),
    [latestTradeId],
  )

  return (
    <div className="panel__grid panel__grid--fixed-height">
      <AgGridReact<Trade>
        theme={tradingTheme}
        columnDefs={columnDefs}
        defaultColDef={defaultColDef}
        getRowId={getRowId}
        rowModelType="infinite"
        cacheBlockSize={CACHE_BLOCK_SIZE}
        suppressMultiSort
        rowClassRules={rowClassRules}
        initialState={initialGridState}
        onGridReady={onGridReady}
      />
    </div>
  )
}

export default TradeBlotter
