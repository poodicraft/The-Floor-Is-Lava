/**
 * PaperEngine's level-designer proxy.
 *
 * The problem this solves: a Hugging Face key put inside the app can be pulled straight
 * back out of the APK by anybody who has the file — `unzip`, `strings`, done. Obfuscating
 * it only buys a minute. The only way to keep a key out of reach and still have the app
 * work with nothing to type is to keep the key somewhere the app is not.
 *
 * So this sits in the middle. The app knows this worker's URL, which is not a secret; the
 * worker knows the key, which is. If the URL ever leaks the worst anyone can do is design
 * levels through the fixed prompt below, and you can rotate the URL or turn it off without
 * touching the key or the app's users.
 *
 * Deploy: see server/README.md. Free tier, no card, ~2 minutes.
 */

/** Only this shape of request is forwarded, whatever else arrives. */
const MAX_BODY_BYTES = 3_000_000; // a 768px JPEG as base64, with room to spare
const MAX_TOKENS = 1500;

/** Hard-coded here rather than taken from the app, so nobody can point it somewhere odd. */
const ALLOWED_MODELS = [
  "Qwen/Qwen2.5-VL-7B-Instruct",
  "Qwen/Qwen2.5-VL-32B-Instruct",
  "Qwen/Qwen2.5-VL-72B-Instruct",
  "meta-llama/Llama-3.2-11B-Vision-Instruct",
  "google/gemma-3-27b-it",
  "mistralai/Mistral-Small-3.1-24B-Instruct-2503",
];

const UPSTREAM = "https://router.huggingface.co/v1/chat/completions";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
    if (request.method !== "POST") return cors(json({ error: "POST only" }, 405));
    if (!env.HF_TOKEN) return cors(json({ error: "The proxy has no HF_TOKEN set." }, 500));

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
    // choose its picture and its prompt, and nothing else.
    const model = ALLOWED_MODELS.includes(body.model) ? body.model : ALLOWED_MODELS[0];
    const forwarded = {
      model,
      max_tokens: Math.min(Number(body.max_tokens) || MAX_TOKENS, MAX_TOKENS),
      temperature: 0.3,
      messages: body.messages,
    };

    if (!Array.isArray(forwarded.messages) || forwarded.messages.length !== 1) {
      return cors(json({ error: "Expected exactly one message." }, 400));
    }

    let upstream;
    try {
      upstream = await fetch(UPSTREAM, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.HF_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(forwarded),
      });
    } catch (error) {
      return cors(json({ error: `Could not reach Hugging Face: ${error}` }, 502));
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
