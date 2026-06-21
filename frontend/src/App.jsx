import { useState, useEffect, useCallback } from 'react'

import versionClient from './api/versionClient'
import Dashboard from './Dashboard'

const App = () => {
  const [versionData, setVersionData] = useState({})

  const fetchVersionData = useCallback(async () => {
    const response = await versionClient.getVersions()
    setVersionData(response)
  }, [])

  useEffect(() => {
    fetchVersionData()
  }, [fetchVersionData])

  return <Dashboard versions={versionData} onRefreshed={fetchVersionData} />
}

export default App
