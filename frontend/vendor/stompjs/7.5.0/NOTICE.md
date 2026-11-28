# Vendored dependency notice — @stomp/stompjs 7.5.0

EstateVista vendors the unmodified upstream UMD distribution of
`@stomp/stompjs` for the Stage 7 chat client. There is no package manager,
bundler, build step or runtime CDN dependency.

| Field | Verified value |
|---|---|
| Library | `@stomp/stompjs` |
| Exact pinned version | `7.5.0` (published 2026-10-15) |
| Official upstream repository | `https://github.com/stomp-js/stompjs` |
| Official release tag | `v7.5.0` |
| Official distribution source | `https://registry.npmjs.org/@stomp/stompjs/-/stompjs-7.5.0.tgz` |
| Distribution tarball SHA-256 | `A2798B41109C9F42135498FABAA594AE4CA5CBC8F91E2B03571BF2C71E1D32C2` |
| Registry `dist.integrity` | `sha512-xh3sdzEYH8M+krQGBOgj+U0BLBHd7ccTj8YM3mVxaULMTW/wx2QBWf448pilRiWZKjglhDmNILxM3W+j/Ch9uw==` (recomputed and matched) |
| Vendored file | `bundles/stomp.umd.js` from that tarball, byte-for-byte |
| Vendored file SHA-256 | `39FED70AE4DFF83709B15A69053BE82248A0C0C5E65EE9A5BC231AE7213562F3` |
| License | Apache-2.0; full upstream text retained in `LICENSE` |
| Upstream copyright notice | Copyright 2018-2020 Deepak Kumar \<deepak@kreatio.com\> |
| Provenance | npm registry publish attestation and SLSA v1 provenance, GitHub Actions workflow `publish.yml` at `refs/tags/v7.5.0`, source commit `dc7bb101fc20dc5f6a53e6869091653fc9023db9` |
| Security advisories | OSV query and GitHub Advisory Database query for `@stomp/stompjs@7.5.0` returned no advisories on 2026-11-28 |

Redistribution requirements observed: the unmodified distribution is copied
locally, the complete Apache-2.0 license text is included, the upstream
copyright and attribution notice is retained, and no modified-file claim is
made. The runtime loads this local file with a classic `<script>` tag; the UMD
build exposes `window.StompJs.Client` and accepts a native `WebSocket` through
`webSocketFactory`. No SockJS, CDN, floating version, package manifest or
build pipeline is introduced.

The application supplies its own bounded reconnect manager and sets
`reconnectDelay: 0` on the client. CONNECT carries the authoritative in-memory
session-bound `X-CSRF-TOKEN` header, and heartbeat settings are supplied by the
approved chat connection manager.
