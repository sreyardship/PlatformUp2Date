import { Box, Container } from '@mui/material'

import ApplicationTable from './ApplicationTable'
import SummaryCards from './SummaryCards'
import TopBar from './TopBar'

const Dashboard = ({ versions, onRefreshed }) => {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <TopBar onRefreshed={onRefreshed} />
      <Container maxWidth="lg" sx={{ flex: 1, py: 3 }}>
        <SummaryCards versions={versions} />
        <ApplicationTable versions={versions} onRefreshed={onRefreshed} />
      </Container>
    </Box>
  )
}

export default Dashboard
