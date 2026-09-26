# Turning on accounts, multiplayer and leaderboards

These features run on **Firebase** (free, no credit card). The Firebase
project must belong to you, so you create it once. It takes about 10
minutes and works in your phone's browser.

Open <https://console.firebase.google.com> and sign in with your Google account.
These steps follow the Firebase console as of September 2026. On a phone,
the left menu is behind the **☰** button at the top left.

## Step 1 — Create the project

1. On the welcome page ("Welcome to Firebase!"), tap the card
   **Get started by setting up a Firebase project**.
2. Name it `Floor Is Lava`. You can switch off "Join the Google Developer
   Program". Tap **Continue**.
3. The "Enable Gemini in Firebase" screen: switch it off (not needed) → **Continue**.
4. Google Analytics: optional, it doesn't affect the game → **Create project** → **Continue**.

You land on the **Project Overview** page.

## Step 2 — Add the app and download its file

1. Tap the **⚙️ gear** next to **Project Overview** (top of the left menu) →
   **Project settings** → **General** tab.
2. Scroll down to **Your apps** and tap the **Android** icon (the robot head).
3. **Android package name:** `com.lava.floorislava` — type it exactly.
   Leave the other two boxes empty.
4. Tap **Register app**.
5. Tap **Download google-services.json** and keep the file.
6. Tap **Next**, **Next**, **Continue to console**.

Lost the file? Same place: **⚙️ → Project settings → General → Your apps**,
tap the app, then **google-services.json**.

## Step 3 — Turn on accounts

1. In the left menu, open **Security** → **Authentication** → **Get started**.
2. On the **Sign-in method** tab, tap **Email/Password**.
3. Turn on the **first** switch (Email/Password). Leave "Email link" off.
4. **Save**.

## Step 4 — Create the database

1. In the left menu, open **Databases & Storage** → under **NoSQL**, tap
   **Firestore** → **Create database**.
2. **Select edition:** choose **Standard** → **Next**.
3. **Database ID & location:** keep the ID as it is and pick a location near
   you (it can't be changed later) → **Next**.
4. **Configure:** choose **Start in production mode** → **Create**.
5. Open the **Rules** tab. Delete everything in the box and paste this:

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

6. Tap **Publish**.

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
