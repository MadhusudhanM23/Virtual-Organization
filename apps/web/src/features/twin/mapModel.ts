/** Cycles through entities with the arrow keys; Escape clears. This is the keyboard path for the SVG map. */
export function nextSelection(
  ids: readonly string[],
  current: string | null,
  step: 1 | -1,
): string | null {
  if (ids.length === 0) return null;
  const i = current ? ids.indexOf(current) : -1;
  if (i === -1) return ids[step === 1 ? 0 : ids.length - 1] ?? null;
  return ids[(i + step + ids.length) % ids.length] ?? null;
}
