# protocol/

The desktop ↔ phone contract for the Pear Remote plugin. Owned by the protocol
agent; the desktop plugin and the Android app implement it but don't change it.

- [`SPEC.md`](SPEC.md): transport, discovery, versioning, pairing,
  authentication, messages, limits, the 1.1 catalog (browse, search,
  thumbnails), and what each side implements.
- `src/`: zod schemas (the normative message definitions), version
  negotiation, pairing URI parsing, limits and close codes. The desktop imports
  these directly.
- `schema/`: JSON Schema generated from `src/`, for tooling and the Android
  side. Don't edit by hand.
- `fixtures/`: valid and invalid examples. Both apps' contract tests run
  against these.
- `tests/`: the desktop-side contract tests.

## Commands

From the repository root:

```sh
node --test 'protocol/tests/*.test.ts'      # contract tests (Node 22.18+)
node protocol/scripts/generate-schema.ts    # regenerate schema/ after changing src/
npx tsc -p protocol/tsconfig.json           # typecheck
```

The code uses only erasable TypeScript syntax and `.ts` import paths so Node
runs it without a build step.

## Contract tests

Fixture files share one shape:

```json
{ "description": "...", "valid": [{ "name": "...", "value": {} }], "invalid": [...] }
```

`client-command.json` and `server-message.json` include the 1.1 catalog
entries (`catalog.*`, the new error codes, and `thumb` on Track/QueueItem); a
1.1 implementation must pass all of them, a 1.0 one only the others.
`pairing-uri.json` uses `uri` (and `payload` for valid entries) instead of
`value`; `negotiation.json` has `cases` with `client`, `supported` and
`expect`.

| Fixture                                        | Desktop check                          | Phone check                                             |
| ---------------------------------------------- | -------------------------------------- | ------------------------------------------------------- |
| `client-handshake.json`, `client-command.json` | parser accepts valid, rejects invalid  | encoder output for each valid entry is equal to `value` |
| `handshake-envelope.json`                      | envelope parser                        | (none)                                                  |
| `server-message.json`                          | everything sent validates              | decoder accepts valid, rejects invalid                  |
| `pairing-uri.json`                             | `parsePairingUri` / `formatPairingUri` | QR parser gives `payload` or rejects                    |
| `negotiation.json`                             | `negotiate`                            | maps desktop errors to the right screen                 |

## Changing the protocol

1. Prefer additive changes: a new message type, command or capability bumps
   the minor in `src/version.ts` and `DESKTOP_SUPPORTED`.
2. Anything else is breaking: bump the major and keep the previous major in
   `DESKTOP_SUPPORTED` for at least one release. Breaking changes, and any
   change to pairing, authentication or listener exposure, need the project
   owner's approval.
3. Update `SPEC.md`, add fixtures, regenerate `schema/`, run the tests.
4. The PR states what changed, whether it's breaking, and what the desktop and
   phone each need to implement.
