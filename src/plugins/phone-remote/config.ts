export interface PhoneRemoteConfig {
  /** Plugin enabled == listener running. Off by default (SPEC section 1). */
  enabled: boolean;
  port: number;
  /**
   * LAN address to bind to. Empty picks the first private address found.
   * Never 0.0.0.0 / ::.
   */
  bindAddress: string;
}

// Identity, instance ID and paired devices are NOT kept in the plugin config:
// that config is mirrored to the renderer (the YT Music page). They live in
// a separate file in userData, see backend/storage.ts.

export const defaultConfig: PhoneRemoteConfig = {
  enabled: false,
  port: 26539,
  bindAddress: '',
};
