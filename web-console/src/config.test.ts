import { describe, expect, it } from 'vitest';
import { webConsoleConfig } from './config';

describe('webConsoleConfig', () => {
  it('uses a safe same-origin API fallback', () => {
    expect(webConsoleConfig.apiBaseUrl).toBe('/api/v1');
  });

  it('never exposes a secret configuration field', () => {
    expect(Object.keys(webConsoleConfig).sort()).toEqual(['apiBaseUrl', 'oidcClientId', 'oidcIssuer']);
  });
});

