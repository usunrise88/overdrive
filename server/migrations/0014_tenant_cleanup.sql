-- The OAuth tables (0003) and the bot's state (0004) are installation-wide now: they live in the `shared` schema
-- (multi-tenancy). The copies the tenant migrations 0003 and 0004 made are unused. On a database moved from the single-user
-- layout the tables were already moved to `shared`, so this removes nothing there.
DROP TABLE IF EXISTS oauth_tokens, oauth_codes, oauth_clients, bot_state;
