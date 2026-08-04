export interface WebConsoleConfig {
  readonly apiBaseUrl: string;
  readonly oidcIssuer: string;
  readonly oidcClientId: string;
}

function configuredValue(value: string | undefined, fallback: string): string {
  const normalized = value?.trim();
  return normalized ? normalized : fallback;
}

export const webConsoleConfig: Readonly<WebConsoleConfig> = Object.freeze({
  apiBaseUrl: configuredValue(import.meta.env.VITE_API_BASE_URL, '/api/v1'),
  oidcIssuer: configuredValue(import.meta.env.VITE_OIDC_ISSUER, 'http://127.0.0.1:8081/realms/workflow-platform'),
  oidcClientId: configuredValue(import.meta.env.VITE_OIDC_CLIENT_ID, 'workflow-web-console'),
});

