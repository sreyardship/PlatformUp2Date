import { Paper } from '@mui/material'
import Title from './Title'
import VersionList from './VersionList'
import RefreshButton from './RefreshButton'

const Display = ({ versions, onRefreshed }) => {
  return (
    <Paper
      sx={{
        display: 'flex',
        flexDirection: 'column',
        pt: '0.6rem',
        alignItems: 'center',
        height: '90%',
        width: '100%',
      }}
    >
      <Title />
      <RefreshButton onRefreshed={onRefreshed} />
      <VersionList versions={versions} />
    </Paper>
  )
}

export default Display
