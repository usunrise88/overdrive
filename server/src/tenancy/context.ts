import type { FastifyRequest } from "fastify";
import type { Db } from "../db.ts";
import type { Services } from "../services.ts";
import type { Tenant, TenantDirectory } from "./directory.ts";

/** What a `/v1` handler works on: the caller's user, the device whose token was presented, and that user's services and data. */
export interface TenantContext {
  userId: number;
  handle: string;
  /** The token's name (`tablet`, `companion`): what `rides.uploaded_by` and the other `device` fields record. */
  device: string;
  services: Services;
  db: Db;
}

declare module "fastify" {
  interface FastifyRequest {
    /** The caller's tenant; set by the `/v1` authentication, absent before it. */
    tenant: TenantContext;
  }
}

/** How a route module gets what it works on: from the request's tenant, never from an object fixed at startup. */
export type Accessor<T> = (request: FastifyRequest) => T;

export type TenantState = "ready" | "failed" | "disabled";

/** One user's running part of the service. `services` and `db` are set only while `state` is `ready`. */
export interface TenantRuntime {
  tenant: Tenant;
  state: TenantState;
  /** Why the tenant failed: key names and messages, never values. */
  error: string | null;
  services: Services | null;
  db: Db | null;
}

/** A {@link TenantDirectory} that also hands out the running tenants: what the HTTP layer needs. */
export interface TenantRuntimeDirectory extends TenantDirectory {
  /** The runtime of a user the directory hosts, or null. */
  runtime(userId: number): TenantRuntime | null;
  /** Every tenant's runtime. */
  runtimes(): TenantRuntime[];
}
