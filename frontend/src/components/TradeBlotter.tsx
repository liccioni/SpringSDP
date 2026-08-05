import { useEffect, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import { AllCommunityModule, ModuleRegistry, type ColDef, type GetRowIdParams } from 'ag-grid-community'
import { connect } from '../services/socket'
import type { Trade } from '../types/trade'

ModuleRegistry.registerModules([AllCommunityModule])

const columnDefs: ColDef<Trade>[] = [
  { field: 'timestamp', headerName: 'Time' },
  { field: 'symbol', headerName: 'Symbol' },
  { field: 'side', headerName: 'Side' },
  { field: 'price', headerName: 'Price' },
  { field: 'quantity', headerName: 'Quantity' },
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

  return (
    <div style={{ width: 700 }}>
      <AgGridReact<Trade> rowData={trades} columnDefs={columnDefs} getRowId={getRowId} domLayout="autoHeight" />
    </div>
  )
}

export default TradeBlotter
