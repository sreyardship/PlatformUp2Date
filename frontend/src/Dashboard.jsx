import { Alert, Box, CircularProgress, Container } from '@mui/material'

import ApplicationTable from './ApplicationTable'
import BackendUnavailable from './BackendUnavailable'
import NotAuthorized from './NotAuthorized'
import SummaryCards from './SummaryCards'
import TopBar from './TopBar'
import { failureKind } from './failureKind'

const LoadingSpinner = () => (
  <Box sx={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
    <CircularProgress />
  </Box>
)

const RefreshErrorBanner = ({ refreshError, onDismiss }) => {
  if (!refreshError) return null
  const { message } = failureKind(refreshError)
  return (
    <Alert severity="error" onClose={onDismiss} sx={{ mb: 2 }}>
      {message} — showing last loaded data
    </Alert>
  )
}

const DashboardBody = ({ versions, onRefreshed, phase, fetchError, refreshError, onDismissRefreshError }) => {
  if (phase === 'loading') return <LoadingSpinner />
  if (phase === 'error') {
    const failure = failureKind(fetchError)
    if (failure.kind === 'not-authorized') return <NotAuthorized />
    return <BackendUnavailable failure={failure} onRetry={onRefreshed} />
  }
  return (
    <>
      <RefreshErrorBanner refreshError={refreshError} onDismiss={onDismissRefreshError} />
      <SummaryCards versions={versions} />
      <ApplicationTable versions={versions} onRefreshed={onRefreshed} />
    </>
  )
}

const Dashboard = ({ versions, onRefreshed, phase, fetchError, refreshError, onDismissRefreshError }) => {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <TopBar onRefreshed={onRefreshed} />
      <Container maxWidth="lg" sx={{ flex: 1, py: 3, display: 'flex', flexDirection: 'column' }}>
        <DashboardBody
          versions={versions}
          onRefreshed={onRefreshed}
          phase={phase}
          fetchError={fetchError}
          refreshError={refreshError}
          onDismissRefreshError={onDismissRefreshError}
        />
      </Container>
    </Box>
  )
}

export default Dashboard
