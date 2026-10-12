import { TwinWorkspace } from './TwinWorkspace';
import { PlaceholderPage } from '../../components/PlaceholderPage';

export function LiveViewPage() {
  return (
    <TwinWorkspace
      titleId="nav.twin.live"
      descriptionId="page.twin.live.description"
      defaultView="3d"
    />
  );
}

export function MapViewPage() {
  return (
    <TwinWorkspace
      titleId="nav.twin.map"
      descriptionId="page.twin.map.description"
      defaultView="2d"
    />
  );
}

export function SimulationPage() {
  return <PlaceholderPage itemId="twin-simulation" />;
}

export function ReplayPage() {
  return <PlaceholderPage itemId="twin-replay" />;
}
