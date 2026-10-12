/** True when a WebGL context can be created here. Never throws; used to choose the 2D fallback (REQ-020). */
export function isWebGLAvailable(): boolean {
  if (typeof document === 'undefined') return false;
  try {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('webgl2') ?? canvas.getContext('webgl');
    return ctx !== null && ctx !== undefined;
  } catch {
    return false;
  }
}
