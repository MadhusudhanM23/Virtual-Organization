import { cn } from '../lib/cn';

/**
 * The mark: a solid block and, offset above it, its wireframe twin. Decorative by default; pass `title`
 * where the mark stands alone (no product name beside it).
 */
export function BrandMark({ className, title }: { className?: string; title?: string }) {
  return (
    <svg
      viewBox="0 0 32 32"
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      className={cn('block', className)}
    >
      {title ? <title>{title}</title> : null}
      <path d="M12 11l7.8 4.5L12 20l-7.8-4.5z" fill="#8bb8ff" />
      <path d="M4.2 15.5L12 20v9l-7.8-4.5z" fill="#3b82f0" />
      <path d="M12 20l7.8-4.5v9L12 29z" fill="#1f5fd1" />
      <path
        d="M20 3l7.8 4.5v9L20 21l-7.8-4.5v-9z"
        fill="none"
        stroke="#e4edff"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      <path
        d="M12.2 7.5L20 12l7.8-4.5M20 12v9"
        fill="none"
        stroke="#e4edff"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      <circle cx="20" cy="3" r="1.7" fill="#ffffff" />
    </svg>
  );
}
