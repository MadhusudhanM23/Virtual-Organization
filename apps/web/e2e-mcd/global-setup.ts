import { resetStory } from './support';

/** Starts every MCD run with an empty story (the steps then fill it in order). */
export default function globalSetup(): void {
  resetStory();
}
