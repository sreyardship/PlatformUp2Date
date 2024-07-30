import { Paper } from '@mui/material'
import Version from './Version'

const Display = ({ data }) => {
  return (
    <Paper
      sx={{
        display: 'flex',
        flexDirection: 'column',
        // justifyContent: 'space-around',
        pt: '1rem',
        alignItems: 'center',
        height: '90%',
        width: '50%',
      }}
    >
      {Object.entries(data).map(([key, val]) => (
        <Version
          name={key}
          ver={val}
        />
      ))}
    </Paper>
  )
}

export default Display
