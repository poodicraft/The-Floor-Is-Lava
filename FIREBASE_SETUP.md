# Turning on accounts, multiplayer and leaderboards

These features run on **Firebase** (free, no credit card). The Firebase
project must belong to you, so you create it once. It takes about 10
minutes and works in your phone's browser.

Open <https://console.firebase.google.com> and sign in with your Google account.

> **Can't find a menu item?** Firebase moved things around. Use the
> **search box at the top of the left menu** ("Search for products") and
> type the name — *Authentication*, *Firestore*, *Settings*.

## Step 1 — Create the project

1. Tap **Create a project** (or **Get started with a Firebase project**).
2. Name it `Floor Is Lava` → **Continue**.
   Make a new project; don't reuse another app's project (like BookQuest).
3. Turn **Google Analytics off** → **Create project** → **Continue**.

## Step 2 — Add the app and download its file

1. On the project page, tap the **Android** icon (or **Add app → Android**).
2. **Android package name:** `com.lava.floorislava` — type it exactly.
   Leave the other fields empty.
3. Tap **Register app**.
4. Tap **Download google-services.json** and keep the file.
5. Tap **Next**, **Next**, **Continue to console** (skip the rest).

Lost the file? **Settings → General**, scroll to **Your apps**, and tap
**google-services.json** to download it again.

## Step 3 — Turn on accounts

1. Search **Authentication** → **Get started**.
2. Under **Sign-in method**, tap **Email/Password**.
3. Switch on the **first** switch (Email/Password). Leave "Email link" off.
4. **Save**.

## Step 4 — Create the database

1. Search **Firestore** → **Create database**.
2. Keep the default edition and pick any location near you → **Next**.
3. Choose **Start in production mode** → **Create**.
4. Open the **Rules** tab. Delete everything in the box and paste this:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() { return request.auth != null; }
    match /users/{uid} {
      allow read: if signedIn();
      allow create, update: if signedIn() && request.auth.uid == uid && request.resource.data.username is string;
      match /friends/{friendUid} {
        allow read: if signedIn() && request.auth.uid == uid;
        allow create, update: if signedIn() && (request.auth.uid == uid || request.auth.uid == friendUid);
        allow delete: if signedIn() && request.auth.uid == uid;
      }
    }
    match /usernames/{name} {
      allow read: if signedIn();
      allow create: if signedIn() && request.resource.data.uid == request.auth.uid;
    }
    match /matches/{code} {
      allow read: if signedIn();
      allow create: if signedIn() && request.resource.data.hostUid == request.auth.uid;
      allow update: if signedIn() && (resource.data.hostUid == request.auth.uid || resource.data.guestUid == request.auth.uid || (resource.data.guestUid == null && request.resource.data.guestUid == request.auth.uid));
    }
  }
}
```

5. Tap **Publish**.

(The same rules, with comments, are in [`firestore.rules`](firestore.rules).
They let each player change only their own score, keep usernames unique,
and let only the two players in a match change it.)

## Step 5 — Hand over the file

**Easiest:** send `google-services.json` to Claude in the chat. Claude adds
it to the project, builds the new APK and sends it back.

**Or yourself on GitHub:** open the repository on the
`claude/apk-compilation-hoqbnd` branch → open the `app` folder → **Add file →
Upload files** → choose `google-services.json` → **Commit changes**. A few
minutes later the new APK appears on the
[release page](https://github.com/poodicraft/The-Floor-Is-Lava/releases/tag/floor-is-lava-apk).

The file isn't a password. It only tells the app which Firebase project to
use; the rules from step 4 decide who can do what.

## Playing multiplayer

1. You and your friend both install the new APK and **create an account**
   (username, email, password).
2. **You:** main menu → **⚔️ MULTIPLAYER** → **CREATE MATCH**. A 5-letter
   code appears. Tap **SEND CODE TO FRIEND** (or just read it out).
3. **Your friend:** main menu → **⚔️ MULTIPLAYER** → type the code under
   **JOIN A FRIEND** → **JOIN**.
4. Wait until both names show **📍 ready** (GPS found), then **you** tap
   **START RACE**. The race uses the difficulty you picked on your main menu.
5. 3-2-1-GO on both phones — first one into their safe zone wins.

- Standing **together**, you both race to the **same** safe zone.
- In **different places**, you each get your **own** zone, the **same
  distance** away.

## If something's wrong

| What you see | What to do |
|---|---|
| "Online play isn't set up yet" | The APK was built without `google-services.json` — do step 5. |
| Creating an account fails with a permission error | The rules weren't published — redo step 4. |
| "No match with code …" | Check the code; the host must stay on the match screen until you join. |
| START RACE stays grey | Both players need **📍 ready**. Go outside and wait a moment for GPS. |
