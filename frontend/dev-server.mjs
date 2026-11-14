/**
 * Zero-dependency static development server for the Vanilla HTML/CSS/JS
 * frontend. It exists only to serve files locally; it is not a production
 * server and it performs no proxying.
 *
 * Usage:
 *   node frontend/dev-server.mjs
 *   FRONTEND_PORT=5501 node frontend/dev-server.mjs
 */

import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

// resolve() removes the trailing separator that fileURLToPath leaves behind,
// so the containment check below compares against a normalized root.
const root = resolve(fileURLToPath(new URL(".", import.meta.url)));
const host = process.env.FRONTEND_HOST ?? "localhost";
const port = Number(process.env.FRONTEND_PORT ?? 5500);

const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".webp": "image/webp",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".txt": "text/plain; charset=utf-8",
  ".md": "text/markdown; charset=utf-8"
};

function sendText(response, status, message) {
  response.writeHead(status, {
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "no-store"
  });
  response.end(message);
}

const server = createServer(async (request, response) => {
  try {
    const requestUrl = new URL(
      request.url ?? "/",
      `http://${request.headers.host ?? "localhost"}`
    );

    let pathname;
    try {
      pathname = decodeURIComponent(requestUrl.pathname);
    } catch {
      sendText(response, 400, "Bad request");
      return;
    }

    if (pathname.endsWith("/")) {
      pathname += "index.html";
    }

    const filePath = resolve(root, `.${pathname}`);
    if (filePath !== root && !filePath.startsWith(`${root}${sep}`)) {
      sendText(response, 403, "Forbidden");
      return;
    }

    const fileStat = await stat(filePath).catch(() => null);
    if (!fileStat?.isFile()) {
      sendText(response, 404, "Not found");
      return;
    }

    const body = await readFile(filePath);
    response.writeHead(200, {
      "Content-Type": MIME_TYPES[extname(filePath).toLowerCase()] ?? "application/octet-stream",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff"
    });
    response.end(body);
  } catch (error) {
    console.error(error);
    sendText(response, 500, "Internal server error");
  }
});

server.listen(port, host, () => {
  console.log(`EstateVista frontend: http://${host}:${port}/`);
  console.log(`Setup check:          http://${host}:${port}/dev/setup-check.html`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
  });
}
