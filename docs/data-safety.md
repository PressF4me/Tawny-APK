# Play Console — Data safety

**Maintained in [`play-submission-runbook.md`](play-submission-runbook.md), step
6, "Data safety".**

Note for whoever fills the form: this build ships a rendezvous URL
(`tawny.rendezvousUrl` in `android/local.properties`, baked into
`BuildConfig.RENDEZVOUS_URL`), so it *can* route a connection over the internet.
The answers must describe that. An earlier version of this file offered a
"LAN-only — collects nothing" column as the default, which is the wrong form to
submit for the build that actually ships.
