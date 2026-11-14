# Security reporting

Do not post credentials, private server URLs, subscription endpoints, device
identifiers, conversations or exploit details in public issues or pull requests.
Use the repository's **Security → Report a vulnerability** private reporting
route when it is enabled. If that route is unavailable, contact the repository
owner privately to agree a secure channel before sending sensitive details.
Private reporting availability must be checked before a public launch.

Include the affected commit/build, platform/version, reproduction steps using a
disposable account or fixture, impact and any proposed mitigation. Redact values
rather than replacing them with another live credential. No response-time or
security-audit guarantee is made.

## Contributor safety

- Keep each saved connection's credentials, cookies and website data isolated.
  Never add TLS/authentication bypasses to make a fixture pass.
- Use disposable test accounts/devices. Keep signing keys, `.env` files, private
  CA keys, private keystores, captures and raw diagnostic logs
  outside the checkout. Public test vectors must be clearly identified.
- Before sharing patches or source archives, scan the tracked tree **and all
  history/refs being shared** with a current secret scanner using full
  redaction. Inspect binary/archive contents too. A scanner's success is not a
  guarantee that secrets or personal data are absent.
- Suspected exposure requires owner review for revocation/rotation and history
  decisions. Do not rotate, rewrite history, or publish a finding automatically.
