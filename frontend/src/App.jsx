import { Container } from '@mui/material'
import { useState, useEffect, useCallback } from 'react'

import versionClient from './api/versionClient'
import Display from './Display'

const App = () => {
  const [versionData, setVersionData] = useState({})

  const fetchVersionData = useCallback(async () => {
    const response = await versionClient.getVersions()
    setVersionData(response)
  }, [])

  useEffect(() => {
    fetchVersionData()
  }, [fetchVersionData])

  return (
    <Container
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
      }}
    >
      <Display versions={versionData} onRefreshed={fetchVersionData} />
    </Container>
  )
}

export default App
