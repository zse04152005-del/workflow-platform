import { webConsoleConfig } from './config';

export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  traceId: string;
  retryable: boolean;
}

export class ApiProblem extends Error {
  readonly problem: ProblemDetails;

  constructor(problem: ProblemDetails) {
    super(problem.detail);
    this.problem = problem;
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${webConsoleConfig.apiBaseUrl}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json, application/problem+json',
      'Content-Type': 'application/json',
      ...init.headers,
    },
    credentials: 'same-origin',
  });
  const body = (await response.json()) as T | ProblemDetails;
  if (!response.ok) throw new ApiProblem(body as ProblemDetails);
  return body as T;
}

export function idempotentRequest<T>(path: string, body: unknown, reason?: string) {
  const key = crypto.randomUUID();
  return apiRequest<T>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': key, ...(reason ? { 'X-Operation-Reason': reason } : {}) },
    body: JSON.stringify(body),
  });
}
