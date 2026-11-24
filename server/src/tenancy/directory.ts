/** A user the service hosts: who they are and which PostgreSQL schema holds their data. */
export interface Tenant {
  userId: number;
  handle: string;
  /** `t_<userId>`. */
  schema: string;
}

/** What kind of bearer token is presented (the column `user_tokens.kind`). */
export type TokenKind = "device" | "mcp";

/**
 * Finds users: by a token's SHA-256 digest (lowercase hex), by id, by Telegram chat. Types only for now; the
 * implementation, backed by `shared.users` and `shared.user_tokens`, comes with the runtime registry.
 */
export interface TenantDirectory {
  /** The user a live (not revoked) token belongs to, with the token's name; null when unknown, revoked or the user is disabled. */
  byToken(sha256: string, kind: TokenKind): Promise<{ tenant: Tenant; tokenName: string } | null>;
  byUserId(id: number): Promise<Tenant | null>;
  byChat(chatId: string): Promise<Tenant | null>;
  list(): Promise<Tenant[]>;
}
