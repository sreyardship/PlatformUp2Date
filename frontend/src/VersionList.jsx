import { Box } from '@mui/material'
import Version from './Version'

const VersionList = ({ versions }) => {
  return (
    <Box
      sx={{
        display: 'flex',
        width: '100%',
        flexDirection: 'column',
        alignItems: 'center',
        overflow: 'auto',
      }}
    >
      {versions &&
        Object.entries(versions).map(([key, val]) => (
          <Version
            key={key}
            name={key}
            ver={val}
          />
        ))}
    </Box>
  )
}

export default VersionList
