import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { SourcesPage } from './pages/SourcesPage'
import { SourceDetailPage } from './pages/SourceDetailPage'
import { RunsPage } from './pages/RunsPage'
import { RunDetailPage } from './pages/RunDetailPage'
import { SchedulesPage } from './pages/SchedulesPage'
import { QuarantinePage } from './pages/QuarantinePage'

export function App() {
  const { username } = useAuth()
  if (!username) return <LoginPage />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="sources" element={<SourcesPage />} />
        <Route path="sources/:id" element={<SourceDetailPage />} />
        <Route path="runs" element={<RunsPage />} />
        <Route path="runs/:id" element={<RunDetailPage />} />
        <Route path="quarantine" element={<QuarantinePage />} />
        <Route path="schedules" element={<SchedulesPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
