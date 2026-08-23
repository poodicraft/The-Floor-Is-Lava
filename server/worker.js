/**
 * PaperEngine's level-designer proxy.
 *
 * The problem this solves: a key put inside the app can be pulled straight back out of the
 * APK by anybody who has the file — `unzip`, `strings`, done. Obfuscating it only buys a
 * minute. The only way to keep a key out of reach and still have the app work with nothing
 * to type is to keep the key somewhere the app is not.
 *
 * So this sits in the middle. The app knows this worker's URL, which is not a secret; the
 * worker knows the key, which is. If the URL ever leaks the worst anyone can do is design
 * levels through the fixed prompt below, and you can rotate the URL or turn it off without
 * touching the key or the app's users.
 *
 * Upstream is Google AI Studio, whose free tier reads pictures without a card on file.
 * (This proxied Hugging Face until v1.7; their routing turned out to need a payment method
 * before it would serve anything at all.)
 *
 * Deploy: see server/README.md. Free tier, no card, ~2 minutes.
 */

/** Only this shape of request is forwarded, whatever else arrives. */
const MAX_BODY_BYTES = 3_000_000; // a 768px JPEG as base64, with room to spare
const MAX_TOKENS = 1500;

/** Hard-coded here rather than taken from the app, so nobody can point it somewhere odd. */
const ALLOWED_MODELS = [
  "gemini-2.5-flash",
  "gemini-flash-latest",
  "gemini-2.0-flash",
  "gemini-2.5-flash-lite",
  "gemini-2.5-pro",
];

const UPSTREAM = "https://generativelanguage.googleapis.com/v1beta/models";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
    if (request.method !== "POST") return cors(json({ error: "POST only" }, 405));
    if (!env.AI_API_KEY) return cors(json({ error: "The proxy has no AI_API_KEY set." }, 500));

    // A shared value the app sends. Anybody who unpacks the APK can read it too, so this
    // is a speed bump against drive-by use of the URL, not a password. Rate limiting is
    // what actually protects the key's budget — see README.
    if (env.APP_SECRET && request.headers.get("x-paperengine") !== env.APP_SECRET) {
      return cors(json({ error: "Not this proxy's app." }, 403));
    }

    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) {
      return cors(json({ error: "That drawing is too big to send." }, 413));
    }

    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      return cors(json({ error: "Body was not JSON." }, 400));
    }

    // Rebuild the request from scratch rather than forwarding what arrived: the app can
    // choose its picture and its prompt, and nothing else. The model rides in the query
    // string because Gemini puts it in the URL rather than the body.
    const asked = new URL(request.url).searchParams.get("model");
    const model = ALLOWED_MODELS.includes(asked) ? asked : ALLOWED_MODELS[0];

    if (!Array.isArray(body.contents) || body.contents.length !== 1) {
      return cors(json({ error: "Expected exactly one turn of contents." }, 400));
    }

    const forwarded = {
      contents: body.contents,
      generationConfig: {
        temperature: 0.3,
        maxOutputTokens: Math.min(
          Number(body.generationConfig?.maxOutputTokens) || MAX_TOKENS,
          MAX_TOKENS,
        ),
      },
    };

    let upstream;
    try {
      upstream = await fetch(`${UPSTREAM}/${model}:generateContent`, {
        method: "POST",
        headers: {
          "x-goog-api-key": env.AI_API_KEY,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(forwarded),
      });
    } catch (error) {
      return cors(json({ error: `Could not reach Google: ${error}` }, 502));
    }

    // Pass the answer through untouched, including failures: the app already turns HTTP
    // statuses into sentences a player can act on, and it should see the real one.
    const text = await upstream.text();
    return cors(
      new Response(text, {
        status: upstream.status,
        headers: { "Content-Type": "application/json" },
      }),
    );
  },
};

function json(value, status) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function cors(response) {
  response.headers.set("Access-Control-Allow-Origin", "*");
  response.headers.set("Access-Control-Allow-Headers", "Content-Type, x-paperengine");
  response.headers.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  return response;
}
