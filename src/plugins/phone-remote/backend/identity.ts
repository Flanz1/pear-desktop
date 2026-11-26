import { createHash, webcrypto, X509Certificate } from 'node:crypto';

import * as x509 from '@peculiar/x509';

import type { StoredIdentity } from './storage.ts';

export interface TlsIdentity {
  keyPem: string;
  certPem: string;
  /** SHA-256 of the DER SubjectPublicKeyInfo, base64url, no padding (SPEC 2.1). */
  fingerprint: string;
}

/** OS-level protection for the private key (Electron safeStorage in the app). */
export interface KeyProtector {
  available(): boolean;
  encrypt(plain: string): Buffer;
  decrypt(data: Buffer): string;
}

const CERT_VALIDITY_MS = 10 * 365 * 24 * 60 * 60 * 1000;
/** Reissue the certificate (same key) when it has less than this left. */
const REISSUE_BEFORE_MS = 30 * 24 * 60 * 60 * 1000;

const toPem = (label: string, der: ArrayBuffer) => {
  const b64 = Buffer.from(der)
    .toString('base64')
    .match(/.{1,64}/g)!
    .join('\n');
  return `-----BEGIN ${label}-----\n${b64}\n-----END ${label}-----\n`;
};

const fromPem = (pem: string) =>
  Buffer.from(
    pem.replace(/-----[A-Z ]+-----/g, '').replace(/\s+/g, ''),
    'base64',
  );

const ALG = { name: 'ECDSA', namedCurve: 'P-256' } as const;

export const spkiFingerprint = (certPem: string): string => {
  const spki = new X509Certificate(certPem).publicKey.export({
    type: 'spki',
    format: 'der',
  });
  return createHash('sha256').update(spki).digest('base64url');
};

const issueCert = async (keys: CryptoKeyPair, now: Date): Promise<string> => {
  const cert = await x509.X509CertificateGenerator.createSelfSigned(
    {
      serialNumber: Buffer.from(webcrypto.getRandomValues(new Uint8Array(16)))
        .toString('hex')
        .replace(/^[89a-f]/, '1'), // keep it positive
      name: 'CN=Pear Remote',
      notBefore: new Date(now.getTime() - 60_000),
      notAfter: new Date(now.getTime() + CERT_VALIDITY_MS),
      keys,
      signingAlgorithm: { name: 'ECDSA', hash: 'SHA-256' },
      extensions: [
        new x509.BasicConstraintsExtension(false, undefined, true),
        new x509.KeyUsagesExtension(x509.KeyUsageFlags.digitalSignature, true),
        new x509.ExtendedKeyUsageExtension([x509.ExtendedKeyUsage.serverAuth]),
      ],
    },
    webcrypto as unknown as Crypto,
  );
  return cert.toString('pem');
};

const importKeys = async (keyPem: string): Promise<CryptoKeyPair> => {
  const subtle = webcrypto.subtle;
  const privateKey = await subtle.importKey(
    'pkcs8',
    fromPem(keyPem),
    ALG,
    true,
    ['sign'],
  );
  // Derive the public key through JWK (drop the private part).
  const jwk = await subtle.exportKey('jwk', privateKey);
  delete jwk.d;
  jwk.key_ops = ['verify'];
  const publicKey = await subtle.importKey('jwk', jwk, ALG, true, ['verify']);
  return { privateKey, publicKey } as CryptoKeyPair;
};

const store = (
  keyPem: string,
  certPem: string,
  protector: KeyProtector,
): StoredIdentity =>
  protector.available()
    ? {
        key: protector.encrypt(keyPem).toString('base64'),
        encrypted: true,
        cert: certPem,
      }
    : {
        key: Buffer.from(keyPem, 'utf8').toString('base64'),
        encrypted: false,
        cert: certPem,
      };

/**
 * Loads the stored identity, creating it on first use (SPEC 2.1). Returns
 * `stored` when the caller has to persist a new or reissued identity.
 */
export const loadOrCreateIdentity = async (
  existing: StoredIdentity | null,
  protector: KeyProtector,
  now = new Date(),
): Promise<{ identity: TlsIdentity; stored: StoredIdentity | null }> => {
  if (existing) {
    try {
      const raw = Buffer.from(existing.key, 'base64');
      const keyPem = existing.encrypted
        ? protector.decrypt(raw)
        : raw.toString('utf8');
      let certPem = existing.cert;
      let stored: StoredIdentity | null = null;
      const cert = new X509Certificate(certPem);
      if (
        new Date(cert.validTo).getTime() - now.getTime() <
        REISSUE_BEFORE_MS
      ) {
        certPem = await issueCert(await importKeys(keyPem), now);
        stored = store(keyPem, certPem, protector);
      } else if (!existing.encrypted && protector.available()) {
        stored = store(keyPem, certPem, protector); // upgrade to protected storage
      }
      return {
        identity: { keyPem, certPem, fingerprint: spkiFingerprint(certPem) },
        stored,
      };
    } catch {
      // Unreadable (e.g. keyring changed): fall through and make a new one.
      // That invalidates existing pairings, which is the safe outcome.
    }
  }
  const keys = (await webcrypto.subtle.generateKey(ALG, true, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const keyPem = toPem(
    'PRIVATE KEY',
    await webcrypto.subtle.exportKey('pkcs8', keys.privateKey),
  );
  const certPem = await issueCert(keys, now);
  return {
    identity: { keyPem, certPem, fingerprint: spkiFingerprint(certPem) },
    stored: store(keyPem, certPem, protector),
  };
};
