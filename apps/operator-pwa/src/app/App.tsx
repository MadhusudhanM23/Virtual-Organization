import { BrowserRouter, Route, Routes } from 'react-router';
import { AppShell } from '../components/AppShell';
import { RequireAuth } from '../components/RequireAuth';
import { LoginPage } from '../features/auth/LoginPage';
import { QueuePage } from '../features/queue/QueuePage';
import { TaskDetailPage } from '../features/tasks/TaskDetailPage';
import { TasksPage } from '../features/tasks/TasksPage';
import { UrgentPage } from '../features/urgent/UrgentPage';
import { UpdatePrompt } from '../components/UpdatePrompt';
import { RuntimeProvider } from './runtime';

export function App() {
  return (
    <RuntimeProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            element={
              <RequireAuth>
                <AppShell />
              </RequireAuth>
            }
          >
            <Route index element={<TasksPage />} />
            <Route path="tasks/:id" element={<TaskDetailPage />} />
            <Route path="queue" element={<QueuePage />} />
            <Route path="urgent" element={<UrgentPage />} />
            <Route path="*" element={<TasksPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
      <UpdatePrompt />
    </RuntimeProvider>
  );
}
