import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchEvidence,
  fetchIncident,
  fetchTimeline,
  type Evidence,
  type Incident,
  type Timeline,
} from './incidentApi';
import { useSafetyStore } from './safetyStore';

export interface IncidentDetailState {
  incident: Incident | null;
  timeline: Timeline | null;
  evidence: Evidence | null;
  error: unknown;
  loading: boolean;
  /** Re-reads everything (after a command ack, or on demand). */
  reload: () => Promise<void>;
}

/**
 * Incident, timeline and evidence of one incident. Re-reads after any live `INCIDENT_*` event of this incident (or a
 * stream resync), so the view never trusts a client-side guess of the new state.
 */
export function useIncidentDetail(id: string | undefined): IncidentDetailState {
  const [incident, setIncident] = useState<Incident | null>(null);
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [evidence, setEvidence] = useState<Evidence | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadedId, setLoadedId] = useState<string | null>(null);
  const revision = useSafetyStore((s) => s.incidentRevision);
  const touched = useSafetyStore((s) => s.touchedIncidents);
  const seq = useRef(0);

  const reload = useCallback(async () => {
    if (!id) return;
    const mine = ++seq.current;
    try {
      const [i, tl, ev] = await Promise.all([
        fetchIncident(id),
        fetchTimeline(id),
        // Evidence is secondary: its failure must not hide the incident.
        fetchEvidence(id).catch(() => null),
      ]);
      if (mine !== seq.current) return;
      setIncident(i);
      setTimeline(tl);
      setEvidence(ev);
      setError(null);
    } catch (e) {
      if (mine !== seq.current) return;
      setError(e);
    } finally {
      if (mine === seq.current) setLoadedId(id);
    }
  }, [id]);

  useEffect(() => {
    // Deferred a tick: the load settles state asynchronously, never inside the effect body.
    const handle = setTimeout(() => void reload(), 0);
    return () => clearTimeout(handle);
  }, [reload]);

  const lastRevision = useRef(revision);
  const touchedBefore = useRef(touched);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (revision === lastRevision.current) return;
    lastRevision.current = revision;
    // An incident event changes `touched` (re-read only for this incident); a resync bumps the revision alone.
    const byEvent = touched !== touchedBefore.current;
    touchedBefore.current = touched;
    if (byEvent && touched[touched.length - 1] !== id) return;
    // Debounced; the timer outlives effect re-runs so an unrelated event cannot cancel a pending re-read.
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      timer.current = null;
      void reload();
    }, 250);
  }, [revision, touched, id, reload]);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const current = loadedId === id;
  return {
    incident: current ? incident : null,
    timeline: current ? timeline : null,
    evidence: current ? evidence : null,
    error: current ? error : null,
    loading: !current,
    reload,
  };
}
