import { Paper, Typography } from '@mui/material'

const Version = ({ name, ver }) => {
  const color = ver.current === ver.latest ? 'success.main' : 'error.main'

  return (
    <Paper
      elevation={2}
      sx={{
        display: 'flex',
        justifyContent: 'space-between',
        width: '95%',
        my: '2px',
        px: '0.6rem',
        py: '1rem',
      }}
    >
      <Typography variant='h4'>{name}:</Typography>
      <Typography
        variant='h4'
        sx={{
          color: color,
        }}
      >
        {ver.current}
      </Typography>
      <Typography variant='h4'>{ver.latest}</Typography>
    </Paper>
  )
}

export default Version
