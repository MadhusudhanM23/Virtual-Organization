import { FlaskConical, Plus } from 'lucide-react';
import { EmptyState } from '../../components/EmptyState';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { summarizeOverlay } from './scenarioModel';
import type { Scenario } from './simulationApi';

const BUTTON_PRIMARY =
  'inline-flex items-center justify-center gap-1.5 rounded-control bg-navy-900 px-3.5 py-2 text-sm font-semibold text-white hover:bg-navy-800';

export interface ScenarioListProps {
  scenarios: Scenario[];
  selectedId: string | null;
  onSelect: (s: Scenario) => void;
  onCreate: () => void;
}

/** Scenario list of the selected site (REQ-071): create button, overlay summary and affected entities per row. */
export function ScenarioList({ scenarios, selectedId, onSelect, onCreate }: ScenarioListProps) {
  const fmt = useSiteFormat();
  return (
    <section
      aria-labelledby="scenario-list-title"
      data-testid="scenario-list"
      className="flex flex-col gap-3 rounded-card border border-line bg-surface p-3 shadow-card"
    >
      <div className="flex items-center justify-between gap-2">
        <h2 id="scenario-list-title" className="text-sm font-semibold text-ink-900">
          Scenarios ({scenarios.length})
        </h2>
        <button type="button" onClick={onCreate} className={BUTTON_PRIMARY} data-testid="scenario-create">
          <Plus aria-hidden="true" className="size-4" />
          New scenario
        </button>
      </div>

      {scenarios.length === 0 ? (
        <EmptyState
          icon={FlaskConical}
          compact
          title="No scenarios yet"
          description="Create a scenario to clone the baseline and try a what-if."
        />
      ) : (
        <ul className="flex flex-col gap-2">
          {scenarios.map((s) => (
            <li key={s.id}>
              <button
                type="button"
                data-testid="scenario-row"
                aria-pressed={s.id === selectedId}
                onClick={() => onSelect(s)}
                className={`w-full rounded-control border px-3 py-2 text-left text-sm ${
                  s.id === selectedId ? 'border-lens-simulate-line bg-lens-simulate-bg' : 'border-line hover:bg-surface-sunken'
                }`}
              >
                <p className="font-semibold text-ink-900">{s.name}</p>
                <p className="mt-0.5 text-xs text-ink-700">{summarizeOverlay(s.overlay)}</p>
                {s.affected_entities.length > 0 ? (
                  <p className="mt-0.5 text-xs text-ink-600">
                    Affects: {s.affected_entities.join(', ')}
                  </p>
                ) : null}
                <p className="mt-0.5 text-[11px] text-ink-600">{fmt.dateTime(s.created_at)}</p>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
