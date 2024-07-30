import { Container } from '@mui/material'
import Colors from './Colors'

import data from './fakeData'
import Display from './Display'

const App = () => {
  return (
    <Container
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
      }}
    >
      <Display data={data} />
      {/* <Colors /> */}
    </Container>
  )
}

export default App
