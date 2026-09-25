# Connecting Floor Is Lava to Firebase

Accounts, multiplayer and the leaderboards run on **Firebase**, Google's free
app backend. The Firebase project has to belong to you (it's tied to your
Google account), so this is a one-time job you do yourself. It takes about
10 minutes and everything below works from a phone browser. The free
"Spark" plan is plenty.

Until it's done, the app still works for solo play and shows an
"Online play isn't set up yet" screen.

## 1. Create the project

1. Go to <https://console.firebase.google.com> and sign in with your Google account.
2. **Create a project** → name it `Floor Is Lava` → you can turn Google
   Analytics **off** → **Create project**.

## 2. Add the Android app and download `google-services.json`

1. On the project home page, press the **Android** icon ("Add app").
2. **Android package name:** `com.lava.floorislava` (must match exactly).
3. Nickname: anything. **SHA-1: leave empty** (not needed for email login).
4. **Register app** → **Download google-services.json**. Keep that file.
5. Skip the remaining "add the SDK" steps — the project already has them.

## 3. Turn on email/password accounts

1. Open **Authentication** (if you can't see it in the left menu, type
   "Authentication" into the search box at the top of the menu).
2. **Get started** → **Sign-in method** → **Email/Password** → **Enable** → **Save**.

## 4. Create the database and paste in the security rules

1. Open **Firestore Database** (search "Firestore" if it isn't in the menu).
2. **Create database** → pick a location close to you → start in
   **production mode** → **Create**.
3. Open the **Rules** tab, delete what's there, and paste the whole content
   of [`firestore.rules`](firestore.rules) from this repository.
4. **Publish**.

The rules make sure players can only change their own profile and score,
usernames stay unique, and only the two players in a match can change it.

## 5. Put `google-services.json` into the project

Upload the file into the **`app`** folder of the repository on the branch
you build from:

1. On GitHub, open the repository, switch to the branch
   (`claude/apk-compilation-hoqbnd`), and open the `app` folder.
2. **Add file → Upload files** → pick `google-services.json` → **Commit changes**.

The file must end up at `app/google-services.json`. It isn't a password:
it only says which Firebase project to talk to, and the security rules
above decide who can do what.

(Or just send the file to Claude in the chat and ask for it to be added.)

## 6. Get the new APK

Committing the file starts the **Build Floor Is Lava APK** workflow. When
it finishes, download `FloorIsLava.apk` from the
[`floor-is-lava-apk` release](https://github.com/poodicraft/The-Floor-Is-Lava/releases/tag/floor-is-lava-apk)
and install it. The app now asks you to create an account.

## How it works

| Feature | Stored in Firestore |
|---|---|
| Account + unique username | Firebase Auth, `usernames/{name}` |
| Profile, points, wins, home area | `users/{uid}` |
| Friends | `users/{uid}/friends/{friendUid}` |
| Multiplayer races | `matches/{code}` |

- **Points:** escaping gives 50 / 100 / 200 points (Easy / Normal / Hard)
  plus 1 point per second left on the clock. Beating a friend in a race
  adds a 100-point bonus.
- **Area leaderboard:** your area is the city your phone's GPS reports,
  updated whenever you play.
- **Friends leaderboard:** you, everyone you've added by username, and
  everyone you've raced against (racing makes you friends automatically).

No indexes are needed; every leaderboard query works with Firestore's
automatic indexes.
