import { describe, expect, it } from 'vitest';
import { proposedId, recommendationListQuery } from './recommendationApi';

describe('recommendationListQuery', () => {
  it('sends one status filter the way the API takes it', () => {
    expect(recommendationListQuery('site-1', ['PROPOSED'], null)).toBe(
      '/recommendations?site_id=site-1&limit=25&status=PROPOSED',
    );
  });

  it('never sends a comma list the API would reject; several statuses are filtered on the client', () => {
    expect(recommendationListQuery('site-1', ['PROPOSED', 'APPROVED'], 'c1')).toBe(
      '/recommendations?site_id=site-1&limit=25&cursor=c1',
    );
    expect(recommendationListQuery('site-1', [], null)).toBe('/recommendations?site_id=site-1&limit=25');
  });
});

describe('proposedId', () => {
  it('reads the recommendation id of the propose ack', () => {
    expect(proposedId({ command_id: 'c', status: 'SUCCEEDED', correlation_id: 'x', result: { recommendation_id: 'r1' } })).toBe('r1');
    expect(proposedId({ command_id: 'c', status: 'SUCCEEDED', correlation_id: 'x' })).toBeNull();
  });
});
