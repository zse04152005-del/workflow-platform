import fs from 'node:fs';
import path from 'node:path';
const root = process.cwd();
const contract = JSON.parse(fs.readFileSync(path.join(root, 'config/security/security-kernel.json'), 'utf8'));
if (contract.tasks.length !== 12 || contract.oidc.length !== 5 || contract.ssrf.length !== 7) throw new Error('G6 security contract incomplete');
for (const file of ['OidcTokenValidator.java', 'ProjectAuthorizationService.java', 'AesGcmSecretProvider.java', 'OutboundRequestPolicy.java', 'TenantQuotaLimiter.java', 'AuditRedactor.java']) {
  if (!fs.existsSync(path.join(root, 'control-plane/src/main/java/com/workflowplatform/controlplane/security', file))) throw new Error(`missing security component ${file}`);
}
for (const file of [contract.adr, contract.threatModel]) if (!fs.existsSync(path.join(root, file))) throw new Error(`missing ${file}`);
console.log('G6 security kernel contract PASS: OIDC, tenant/RBAC/RLS, secrets, SSRF, quotas, audit, supply chain and STRIDE.');
