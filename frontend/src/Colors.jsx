import { Typography } from '@mui/material'

const Colors = () => {
  return (
    <>
      <Typography
        variant='h6'
        sx={{
          color: 'primary.main',
        }}
      >
        Primary
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'secondary.main',
        }}
      >
        Secondary
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'error.main',
        }}
      >
        Error
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'warning.main',
        }}
      >
        Warning
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'info.main',
        }}
      >
        Info
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'success.main',
        }}
      >
        Success
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'grey.700',
        }}
      >
        Grey 700
      </Typography>
      <Typography
        variant='h6'
        sx={{
          color: 'grey.900',
        }}
      >
        Grey 900
      </Typography>
    </>
  )
}

export default Colors
