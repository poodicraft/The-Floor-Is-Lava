# The level-designer proxy

**Why this exists:** a key put inside the app is not secret. An APK is a zip —
`unzip app.apk && strings classes.dex | grep AIza` gets the key back in about ten seconds, and
obfuscation only makes that a two-minute job instead. There is no way to ship a key inside an
app and keep it private. The only real fix is to keep the key somewhere the app is not.

This worker is that somewhere. The app knows the worker's **URL**, which is not a secret. The
worker knows the **key**, which is. Nothing to type in the app, and nothing worth stealing in
the APK.

Free, no card, about two minutes.

Upstream is **Google AI Studio**. This proxied Hugging Face until v1.7; their router turned
out to refuse every model unless the account has a payment method on file, which defeats the
point of a free feature.

## Get a key

<https://aistudio.google.com/apikey> → **Create API key**. Sign in with a Google account,
and that is the whole of it: no card, no billing screen. The key starts with `AIza`.

## Deploy it

1. Sign up at <https://dash.cloudflare.com> (free plan).
2. **Workers & Pages → Create → Worker**. Give it a name, e.g. `paperengine`. Deploy the
   placeholder it offers.
3. **Edit code**, delete what is there, paste in [`worker.js`](worker.js), and deploy.
4. **Settings → Variables and Secrets → Add**:
   - `AI_API_KEY` — type **Secret** — the `AIza…` key from above.
   - `APP_SECRET` — type **Secret** — optional; any random string. If you set it, put the
     same string in `AI_APP_SECRET` when building the app (below).
5. Copy the worker's URL: `https://paperengine.<your-name>.workers.dev`.

That URL goes into the app at build time (see below), so the app arrives already working.

## Keep it from being abused

The URL is inside the APK, so treat it as public. Two things worth doing in the Cloudflare
dashboard, both free:

- **Security → WAF → Rate limiting rules**: something like 20 requests per minute per IP.
  This is what actually protects the key's daily quota.
- The worker only ever forwards a request it has rebuilt itself, with the model picked from
  a fixed list and the token count capped, so the URL cannot be used as a general-purpose
  Gemini account.

If it is ever abused: change the worker's name (new URL), or rotate `AI_API_KEY`. Neither
requires touching the key that is in anybody's hands, because it never was.

## Building the app against it

The URL is not a secret, so it can go straight in the repository:

```properties
# gradle.properties  (or -PaiProxyUrl=… on the command line)
aiProxyUrl=https://paperengine.your-name.workers.dev
aiAppSecret=whatever-you-put-in-APP_SECRET
```

For CI builds, add the same two as **repository secrets** named `AI_PROXY_URL` and
`AI_APP_SECRET` (Settings → Secrets and variables → Actions). The workflow passes them
through automatically, and neither is worth hiding if it leaks.

## The other option, and what it costs you

If you would rather not run anything, the build can bake the key straight into the APK: add a
repository secret named `AI_API_KEY` and every CI build will carry it, with the key staying
out of the source. The app then works out of the box with nothing to type.

Be clear-eyed about what that means: **anybody who gets the APK gets the key.** For a build
that only ever lives on your own phone that is a reasonable trade. For one you send to
friends, or put anywhere public, it is not — one of them uploads it somewhere, a scraper
finds it, and the quota is somebody else's.

The proxy is the same amount of free and does not have that problem.
