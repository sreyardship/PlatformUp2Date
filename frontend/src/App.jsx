import { useState, useEffect, useCallback, useRef } from 'react'

import versionClient from './api/versionClient'
import Dashboard from './Dashboard'

const App = () => {
  const [versionData, setVersionData] = useState(null)
  const [fetchPhase, setFetchPhase] = useState('loading')
  const [fetchError, setFetchError] = useState(null)
  const [refreshError, setRefreshError] = useState(null)
  const hasLoadedOnce = useRef(false)

  const fetchVersionData = useCallback(async () => {
    try {
      const response = await versionClient.getVersions()
      hasLoadedOnce.current = true
      setVersionData(response)
      setFetchPhase('loaded')
      setFetchError(null)
      setRefreshError(null)
    } catch (err) {
      if (!hasLoadedOnce.current || err?.status === 403) {
        // A 403 always flips the whole screen to Not-authorized, even on refresh — missing
        // entitlement does not self-heal, so leaving stale board data under a banner would be
        // misleading.
        setFetchPhase('error')
        setFetchError(err)
      } else {
        // A refresh failure after first load: keep existing data and record error for banner
        setRefreshError(err)
      }
    }
  }, [])

  useEffect(() => {
    fetchVersionData()
  }, [fetchVersionData])

  return (
    <Dashboard
      versions={versionData ?? {}}
      onRefreshed={fetchVersionData}
      phase={fetchPhase}
      fetchError={fetchError}
      refreshError={refreshError}
      onDismissRefreshError={() => setRefreshError(null)}
    />
  )
}

export default App
