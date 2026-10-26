import { TwinWorkspace } from './TwinWorkspace';

export function LiveViewPage() {
  return (
    <TwinWorkspace
      titleId="nav.twin.live"
      descriptionId="page.twin.live.description"
      defaultView="3d"
      command
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

export { SimulatePage as SimulationPage } from '../simulate/SimulatePage';
export { ReplayPage } from '../replay/ReplayPage';
