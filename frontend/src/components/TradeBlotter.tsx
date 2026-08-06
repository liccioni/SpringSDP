import { useEffect, useMemo, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import {
  AllCommunityModule,
  ModuleRegistry,
  type ColDef,
  type GetRowIdParams,
  type ICellRendererParams,
  type RowClassRules,
} from 'ag-grid-community'
import { connect } from '../services/socket'
import { tradingTheme } from '../theme/tradingTheme'
import type { Trade } from '../types/trade'

ModuleRegistry.registerModules([AllCommunityModule])

function SideBadge({ value }: ICellRendererParams<Trade, Trade['side']>) {
  return <span className={`side-badge side-badge--${value?.toLowerCase()}`}>{value}</span>
}

function formatTimeOfDay(timestamp: string): string {
  return timestamp.split('T')[1]?.slice(0, 8) ?? timestamp
}

const columnDefs: ColDef<Trade>[] = [
  { field: 'timestamp', headerName: 'Time', valueFormatter: (params) => formatTimeOfDay(params.value), flex: 0.8 },
  { field: 'symbol', headerName: 'Symbol', cellClass: 'cell-symbol', flex: 1 },
  { field: 'side', headerName: 'Side', cellRenderer: SideBadge, flex: 0.7 },
  { field: 'price', headerName: 'Price', type: 'rightAligned', flex: 1 },
  { field: 'quantity', headerName: 'Quantity', type: 'rightAligned', flex: 1.1 },
]

function getRowId(params: GetRowIdParams<Trade>) {
  return params.data.id
}

function TradeBlotter() {
  const [trades, setTrades] = useState<Trade[]>([])

  useEffect(() => {
    const socket = connect((envelope) => {
      if (envelope.type === 'TRADE_CREATED') {
        const trade = envelope.payload as Trade
        setTrades((current) => [trade, ...current])
      }
    })

    return () => socket.close()
  }, [])

  const rowClassRules = useMemo<RowClassRules<Trade>>(
    () => ({
      'trade-row--new': (params) => params.data?.id === trades[0]?.id,
    }),
    [trades],
  )

  return (
    <div className="panel__grid">
      <AgGridReact<Trade>
        theme={tradingTheme}
        rowData={trades}
        columnDefs={columnDefs}
        getRowId={getRowId}
        domLayout="autoHeight"
        rowClassRules={rowClassRules}
      />
    </div>
  )
}

export default TradeBlotter
