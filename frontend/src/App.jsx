import { Container } from '@mui/material'
import { useState, useEffect } from 'react'

import versionClient from './api/versionClient'
import Display from './Display'

const App = () => {
  const [versionData, setVersionData] = useState({})

  useEffect(() => {
    const fetchVersionData = async () => {
      const response = await versionClient.getVersions()
      setVersionData(response)
    }
    fetchVersionData()
  }, [])

  return (
    <Container
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
      }}
    >
      <Display versions={versionData} />
    </Container>
  )
}

export default App
