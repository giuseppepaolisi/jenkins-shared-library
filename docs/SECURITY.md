# Sicurezza nella Jenkins Shared Library Enterprise

## Principi Fondamentali

1. **Zero Trust Credentials** — nessuna credenziale hardcoded, runtime binding
2. **Defense in Depth** — multiple layer di sicurezza (branch, log, input)
3. **Least Privilege** — ogni operazione con il minimo privilegio necessario
4. **Audit Completo** — ogni operazione tracciata con log strutturati
5. **Supply Chain Security** — dalla firma alla provenienza

## Implementazioni

### 1. Credential Management

| Metodo | Utilizzo | Backend |
|--------|----------|---------|
| `SecretManager.withCredentials()` | Runtime binding | Jenkins Credentials |
| `SecretManager.fromVault()` | HashiCorp Vault | Vault KV v2 |
| `SecretManager.fromAwsSecretsManager()` | AWS Secrets Manager | SM API |

### 2. Log Sanitization

Pattern automaticamente mascherati nei log:
- Password, token, secret key in formato `key=value`
- JWT token (formato `xxxxx.yyyyy.zzzzz`)
- Bearer authorization header
- Base64 stringhe lunghe (>40 caratteri)
- AWS session token

### 3. Branch Protection

| Ambiente | Branch Consentiti |
|----------|------------------|
| Production | `main`, `release/*`, `hotfix/*` |
| Staging | `main`, `develop`, `release/*`, `feature/*` |
| Development | Qualsiasi |

### 4. Supply Chain Security

```
Codice → Build → Test → Scan (Trivy) → Sign (Cosign) → Attest → SBOM → Push
```

Ogni artefatto ha:
- Firma crittografica (Cosign)
- SBOM (CycloneDX/SPDX)
- Vulnerability scan gate
- Provenance attestation

### 5. Audit Trail

Ogni operazione AWS, deploy, e modifica produce log JSON strutturati:
```json
{
  "timestamp": "2026-06-06T22:30:00.000+0200",
  "level": "INFO",
  "message": "Assume role completato",
  "job": "myapp/deploy",
  "build": "142",
  "branch": "main",
  "stage": "AWS Auth"
}
```