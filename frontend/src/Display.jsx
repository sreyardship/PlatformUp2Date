import { Paper } from '@mui/material'
import Title from './Title'
import VersionList from './VersionList'

const Display = ({ versions }) => {
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
      <VersionList versions={versions} />
    </Paper>
  )
}

export default Display
