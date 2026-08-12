import ExecutionConfirmation from './components/ExecutionConfirmation'
import Greeting from './components/Greeting'
import PriceGrid from './components/PriceGrid'
import TradeBlotter from './components/TradeBlotter'
import { logout } from './services/socket'

// No login gating here: identity comes from the Spring Session cookie set
// by the Keycloak redirect flow (see ADR 0020). If a WebSocket connection
// below fails to authenticate, socket.ts redirects the browser to start
// that flow - there's nothing for App itself to check up front.
function App() {
  return (
    <main className="shell">
      <header className="shell__header">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">
            SDP
          </span>
          <h1 className="brand__title">Single Dealer Platform</h1>
        </div>
        <div className="shell__header-actions">
          <Greeting />
          <button type="button" className="logout-button" onClick={logout}>
            Logout
          </button>
        </div>
      </header>
      <div className="shell__body">
        <section className="panel" aria-label="Rates">
          <h2 className="panel__title">Rates</h2>
          <PriceGrid />
          <ExecutionConfirmation />
          <p className="panel__hint">Set a quantity, then use Sell or Buy to deal at the shown price.</p>
        </section>
        <section className="panel" aria-label="Blotter">
          <h2 className="panel__title">Blotter</h2>
          <TradeBlotter />
        </section>
      </div>
    </main>
  )
}

export default App
