/**
 * The two pages of the authorization endpoint: the one where the owner approves a client with the
 * passphrase, and the plain message for a request that cannot be answered by redirecting back. Dark,
 * one column, no script and no external file (the repository's design principles, kept small).
 */

const escapes: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};

/** Text that is safe inside an HTML element or a quoted attribute. */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (char) => escapes[char] ?? char);
}

const STYLE = `
:root{color-scheme:dark}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0b0b0c;color:#ededed;font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
main{width:min(420px,calc(100vw - 32px));padding:24px 0}
.card{border:1px solid #2a2a2e;border-radius:12px;padding:24px;background:#111113}
h1{font-size:20px;font-weight:600;margin:0 0 12px}
p{margin:0 0 16px;color:#a1a1a6}
strong{color:#ededed;font-weight:600}
.host{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#ededed}
label{display:block;margin:0 0 6px;font-size:14px;color:#a1a1a6}
input[type=password]{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;border:1px solid #2a2a2e;background:#0b0b0c;color:inherit;font:inherit}
input[type=password]:focus{outline:2px solid #ededed;outline-offset:1px}
.row{display:flex;gap:12px;margin-top:16px}
button{flex:1;padding:12px;border-radius:8px;border:1px solid #2a2a2e;background:transparent;color:#ededed;font:inherit;cursor:pointer}
button.primary{background:#ededed;color:#0b0b0c;border-color:#ededed;font-weight:600}
.error{margin:12px 0 0;color:#ff8a80}
`;

function shell(title: string, body: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body><main><div class="card">${body}</div></main></body>
</html>
`;
}

export interface AuthorizePage {
  clientName: string;
  /** `host:port` the owner is sent back to after approving. */
  redirectHost: string;
  /** The redirect goes to an app on the same machine as the browser. */
  redirectIsLocal: boolean;
  /** The request's parameters, carried through the form untouched. */
  hidden: Record<string, string>;
  /** A line to show under the form: a wrong passphrase, a lock. */
  problem?: string;
}

/** Where the owner types the passphrase and approves or refuses the client. */
export function authorizePage(page: AuthorizePage): string {
  const hidden = Object.entries(page.hidden)
    .map(
      ([name, value]) =>
        `<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">`,
    )
    .join("");
  const where = page.redirectIsLocal
    ? "an app on this device"
    : `<span class="host">${escapeHtml(page.redirectHost)}</span>`;
  return shell(
    "Connect to Overdrive",
    `<h1>Connect to Overdrive</h1>
<p><strong>${escapeHtml(page.clientName)}</strong> asks to read your rides and profile, and to change your workout plan and profile.</p>
<p>After you decide, your browser goes back to ${where}.</p>
<form method="post" action="/oauth/authorize">${hidden}
<label for="passphrase">Passphrase</label>
<input id="passphrase" type="password" name="passphrase" autocomplete="current-password" autofocus>
${page.problem === undefined ? "" : `<p class="error" role="alert">${escapeHtml(page.problem)}</p>`}
<div class="row"><button type="submit" name="decision" value="deny">Deny</button><button class="primary" type="submit" name="decision" value="approve">Approve</button></div>
</form>`,
  );
}

/** A request that cannot be answered by going back to the client: its address cannot be trusted. */
export function messagePage(title: string, message: string): string {
  return shell(title, `<h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p>`);
}
