import { useState, type ReactNode } from 'react';
import { CircleAlert, FlaskConical } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { ApiError } from '../../lib/api/client';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useUiStore } from '../../stores/uiStore';
import { RunPanel } from './RunPanel';
import { ScenarioBuilderForm } from './ScenarioBuilderForm';
import { ScenarioList } from './ScenarioList';
import { createScenario, newIdempotencyKey, type CreateScenarioBody, type Scenario } from './simulationApi';
import { useScenarios } from './useSimulateData';
import { useSimulateLens } from './useSimulateLens';
import type { FieldError } from './scenarioModel';

/** Route `/twin/simulation` (REQ-070..077, Plan 6.4): the SIMULATE lens — builder, run, compare, affected
 * entities. Mirrors the REPLAY lens page structure (`features/replay/ReplayPage.tsx`). */
export function SimulatePage() {
  useDocumentTitle('Simulation');
  useSimulateLens();
  const siteId = useUiStore((s) => s.selectedSiteId);
  const { state, reload, addScenario } = useScenarios(siteId);
  const [mode, setMode] = useState<'list' | 'builder'>('list');
  const [selected, setSelected] = useState<Scenario | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleCreate = async (body: CreateScenarioBody): Promise<FieldError[] | null> => {
    setSubmitting(true);
    try {
      const ack = await createScenario(body, newIdempotencyKey());
      if (ack.result) {
        addScenario(ack.result);
        setSelected(ack.result);
        setMode('list');
      }
      return null;
    } catch (e) {
      if (e instanceof ApiError && e.status === 400 && e.fieldErrors) return e.fieldErrors;
      return [{ field: 'name', message: e instanceof Error ? e.message : 'Could not create the scenario.' }];
    } finally {
      setSubmitting(false);
    }
  };

  const header = (
    <header className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight text-ink-900">
          <FlaskConical aria-hidden="true" className="size-6 text-lens-simulate-fg" />
          Simulation
        </h1>
        <p className="mt-1 max-w-2xl text-sm text-ink-600">
          Clone the baseline, change variables and compare outcomes. Results are scenario output, not live data.
        </p>
      </div>
      <div className="flex items-center gap-2" data-testid="simulate-truth">
        <DataModeBadge dataMode="SIMULATED" lens="SIMULATE" />
      </div>
    </header>
  );

  const shell = (children: ReactNode) => (
    <div className="mx-auto flex w-full max-w-[1400px] flex-col gap-4 p-4 md:p-6" data-testid="simulate-page" data-lens="SIMULATE">
      {header}
      {children}
    </div>
  );

  if (!siteId) {
    return shell(
      <div className="grid min-h-[22rem] place-items-center rounded-card border-2 border-lens-simulate-line bg-surface shadow-card">
        <EmptyState icon={FlaskConical} title="No site selected" description="Select a site to view its scenarios." />
      </div>,
    );
  }

  if (state.status === 'error') {
    return shell(
      <div className="grid min-h-[22rem] place-items-center rounded-card border-2 border-lens-simulate-line bg-surface shadow-card">
        <div role="alert" className="flex max-w-md flex-col items-center gap-3 p-6 text-center">
          <CircleAlert aria-hidden="true" className="size-8 text-danger-fg" />
          <p className="font-semibold text-ink-900">
            {state.forbidden ? 'Your role cannot view simulations for this site' : 'Scenarios could not be loaded'}
          </p>
          {!state.forbidden ? <p className="text-sm text-ink-600">{state.message}</p> : null}
          <button
            type="button"
            onClick={reload}
            className="rounded-control bg-navy-900 px-4 py-2 text-sm font-semibold text-white hover:bg-navy-800"
          >
            Retry
          </button>
        </div>
      </div>,
    );
  }

  if (state.status !== 'ready') {
    return shell(
      <div className="grid min-h-[22rem] place-items-center rounded-card border-2 border-lens-simulate-line bg-surface shadow-card">
        <p role="status" className="text-sm text-ink-700">
          Loading scenarios…
        </p>
      </div>,
    );
  }

  return shell(
    <div className="grid gap-4 xl:grid-cols-[22rem_minmax(0,1fr)]">
      <div className="flex flex-col gap-4">
        {mode === 'builder' ? (
          <ScenarioBuilderForm siteId={siteId} onCancel={() => setMode('list')} onSubmit={handleCreate} submitting={submitting} />
        ) : (
          <ScenarioList
            scenarios={state.items}
            selectedId={selected?.id ?? null}
            onSelect={setSelected}
            onCreate={() => setMode('builder')}
          />
        )}
      </div>
      <div className="min-w-0">
        {selected ? (
          <RunPanel key={selected.id} scenario={selected} />
        ) : (
          <div className="grid min-h-[16rem] place-items-center rounded-card border border-dashed border-line-strong bg-surface p-6 text-center shadow-card">
            <p className="text-sm text-ink-600">Select a scenario to run it and compare results.</p>
          </div>
        )}
      </div>
    </div>,
  );
}
