import { useEffect, type ReactNode } from 'react';
import { TruthLabel, type TruthKind } from './TruthLabel';

/** One screen: the page title (h1), the truth label, then the content. Sets the document title. */
export function Screen({
  title,
  truth,
  cachedAt,
  children,
}: {
  title: string;
  truth: TruthKind;
  cachedAt?: number | null | undefined;
  children: ReactNode;
}) {
  useEffect(() => {
    document.title = title;
  }, [title]);
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4 px-4 py-4">
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-2xl font-bold text-ink-900">{title}</h1>
        <TruthLabel kind={truth} cachedAt={cachedAt} />
      </div>
      {children}
    </div>
  );
}
