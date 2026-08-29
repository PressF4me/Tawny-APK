# Play Console — Data safety form answers

Fill these into Play Console → *App content → Data safety*. Two columns: answer
the **LAN-only** build as-is; add the **Remote** rows only if you ship a build
with a rendezvous/TURN URL configured.

## Does your app collect or share any of the required user data types?

- **LAN-only build:** **No.** Media travels directly between the user's own
  devices on their local network; the developer neither receives nor stores any
  user data. No account, no identifiers, no analytics.
- **Remote build:** **Yes** — the minimum required to route a connection (see
  below). Still no account, no analytics, no ads.

## Data types (Remote build)

| Type | Collected | Shared | Ephemeral / stored | Purpose | Required? | Encrypted in transit |
|---|---|---|---|---|---|---|
| **Device or other IDs → IP address** | Yes | No | Processed ephemerally, not stored | App functionality (establish/route the peer connection) | Required | Yes |
| **Audio → Voice or sound recordings** | "Collected" in the form's sense (transits infra) | No | **Not stored.** End-to-end encrypted; a TURN relay may forward encrypted packets it cannot read | App functionality (live two-way audio) | Required | Yes (E2EE) |
| **Photos and videos → Videos** | Same as audio | No | Not stored. E2EE; relay cannot read | App functionality (live video) | Required | Yes (E2EE) |

Everything else: **No.**

- Location: No.
- Personal info (name, email, address, phone, etc.): No.
- Financial info: No.
- Contacts: No.
- App activity / browsing / search history: No.
- Installed apps: No.
- Advertising ID: No.

## Security practices

- **Data is encrypted in transit:** Yes (DTLS-SRTP for media; WSS for signalling
  on the remote path).
- **Users can request that data be deleted:** Yes — there is essentially nothing
  stored server-side; clearing app data removes all local state. Provide the
  support contact from the privacy policy.
- **Committed to Play Families Policy:** the app is not targeted to children.
- **Independent security review:** optional; you may cite `SECURITY.md`.

## "Data is processed ephemerally" note

Where the form allows free text, state: *"The rendezvous service introduces two
of the user's own devices and, only when a direct path is unavailable, relays
their end-to-end-encrypted media. It cannot decrypt media, does not record or
persist any stream, and does not log IP addresses for identification. No content
is retained after a session ends."*
