import type { DeviceToken } from "../config.ts";
import type { Db } from "../db.ts";
import { sha256Hex } from "../hash.ts";
import type { Services } from "../services.ts";
import type { TenantRuntime, TenantRuntimeDirectory } from "./context.ts";
import type { Tenant } from "./directory.ts";
import { TokenTable } from "./tokens.ts";

/** The user id and handle of the one tenant of the single-tenant path (the owner). */
export const SINGLE_TENANT_ID = 1;
export const SINGLE_TENANT_HANDLE = "owner";

/**
 * A directory of one tenant: user 1, handle `owner`, the given database and services, the tokens of `DEVICE_TOKENS`.
 * It is what `buildApp({ config, db })` uses, so the single-user path and its tests keep working unchanged.
 */
export function singleTenantDirectory(options: {
  devices: readonly DeviceToken[];
  db: Db;
  services: Services;
}): TenantRuntimeDirectory {
  const tenant: Tenant = {
    userId: SINGLE_TENANT_ID,
    handle: SINGLE_TENANT_HANDLE,
    schema: `t_${SINGLE_TENANT_ID}`,
  };
  const runtime: TenantRuntime = {
    tenant,
    state: "ready",
    error: null,
    services: options.services,
    db: options.db,
  };
  const tokens = new TokenTable();
  for (const device of options.devices) {
    tokens.add(sha256Hex(device.token), {
      userId: tenant.userId,
      kind: "device",
      name: device.name,
    });
  }
  return {
    async byToken(sha256, kind) {
      const hit = tokens.find(sha256, kind);
      return hit === null ? null : { tenant, tokenName: hit.name };
    },
    async byUserId(id) {
      return id === tenant.userId ? tenant : null;
    },
    async byChat() {
      return null;
    },
    async list() {
      return [tenant];
    },
    runtime: (id) => (id === tenant.userId ? runtime : null),
    runtimes: () => [runtime],
  };
}
