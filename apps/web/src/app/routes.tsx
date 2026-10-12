import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';
import { AppShell } from './layout/AppShell';
import LoginPage from '../features/auth/LoginPage';
import { RequireAuth } from '../features/auth/RequireAuth';
import { DEFAULT_PATH } from './navigation';
import AnalyticsPage from '../features/analytics';
import AssetsPage from '../features/assets';
import AssistantPage from '../features/assistant';
import FleetPage from '../features/fleet';
import IncidentDetailPage from '../features/incidents/IncidentDetailPage';
import IncidentsPage from '../features/incidents/IncidentsPage';
import InventoryPage from '../features/inventory';
import NotFoundPage from '../features/not-found';
import OperationsPage from '../features/operations';
import OrdersPage from '../features/orders';
import OrganizationPage from '../features/organization';
import OverviewPage from '../features/overview';
import PeoplePage from '../features/people';
import ReportsPage from '../features/reports';
import SettingsPage from '../features/settings';
import { LiveViewPage, MapViewPage, ReplayPage, SimulationPage } from '../features/twin';

/** Route table, exported separately so tests can mount it in a memory router. */
export const routeObjects: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to={DEFAULT_PATH} replace /> },
      { path: 'overview', element: <OverviewPage /> },
      { path: 'organization', element: <OrganizationPage /> },
      { path: 'operations', element: <OperationsPage /> },
      { path: 'incidents', element: <IncidentsPage /> },
      { path: 'incidents/:id', element: <IncidentDetailPage /> },
      { path: 'twin/live', element: <LiveViewPage /> },
      { path: 'twin/map', element: <MapViewPage /> },
      { path: 'twin/simulation', element: <SimulationPage /> },
      { path: 'twin/replay', element: <ReplayPage /> },
      { path: 'orders', element: <OrdersPage /> },
      { path: 'inventory', element: <InventoryPage /> },
      { path: 'assets', element: <AssetsPage /> },
      { path: 'people', element: <PeoplePage /> },
      { path: 'fleet', element: <FleetPage /> },
      { path: 'analytics', element: <AnalyticsPage /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'reports', element: <ReportsPage /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export const router = createBrowserRouter(routeObjects);
