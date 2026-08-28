# Turning on Google sign in and cloud backup

The app ships with the sign in flow, the account card and the whole backup
merge already written. What it cannot ship is a Google project — that has to
belong to you, because it is tied to your Google account and to this app's
signing fingerprint. Until you add one, the account card in Settings says
"Cloud backup is not set up in this build" and everything else works normally,
fully offline.

Setup takes about ten minutes and is free. No credit card, no billing plan:
Firebase Authentication and the Firestore free tier cover a school's worth of
students comfortably.

## What you will be asked for

| Field | Value |
| --- | --- |
| Package name | `com.poodicraft.bookquest` |
| Debug signing SHA-1 | `16:D2:E4:C6:C9:2D:8C:5E:77:FB:12:42:C7:99:D6:59:69:39:32:1D` |

That SHA-1 comes from `keystore/debug.keystore`, which is committed to this
repository on purpose. Android's debug key is not a secret — its password is
the well known `android` — and pinning it means every APK, yours or CI's, is
signed with the same fingerprint, so Google sign in keeps working across
builds. You can re-derive it any time with:

```bash
keytool -list -v -keystore keystore/debug.keystore -storepass android -alias androiddebugkey
```

## The order matters

Do not download `google-services.json` when the console first offers it.

Google sign in needs an OAuth **web client**, and Firebase only creates that
once you switch the Google provider on in Authentication. A file downloaded
before that step is missing it, the build has no `default_web_client_id` to
compile in, and the app still reports "not configured" even though the file
is there. So: turn everything on first, download the file last.

> **A note on the console menu.** Firebase reorganised its left hand menu, so
> the old *Build* section no longer exists: Authentication now sits under
> **Security** and Firestore under **Databases & Storage**. The steps below use
> the *Search for products* box at the top of that menu instead, which works
> whichever layout you are looking at. The *Project settings* page has moved
> too — it is now **Settings → General**.

## Steps

1. **Create the project.** Go to <https://console.firebase.google.com>,
   click *Create a project*, and give it a name (`BookQuest` is fine). Google
   Analytics is not needed — turn it off.

2. **Register the Android app.** In the project, click the Android icon (or
   *Add app → Android*) and fill in:
   - Android package name: `com.poodicraft.bookquest`
   - Debug signing certificate SHA-1: the value from the table above.

   Click through the rest of the wizard. **Skip the download and the "add the
   SDK" instructions** — the code is already in this repository.

3. **Turn on Google sign in.** Type `Authentication` into the *Search for
   products* box at the top of the left menu and open it, then *Get started*,
   pick **Google** from the provider list, toggle it to enabled, choose a
   project support email, and save. This is the step that creates the web
   client the app needs.

4. **Create the database.** Search for `Firestore` the same way and open
   *Firestore Database → Create database*. Pick a location near your school
   and start in **production mode**. Then open the **Rules** tab and replace the contents with this, so
   each student can only ever read and write their own document:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{userId} {
         allow read, write: if request.auth != null && request.auth.uid == userId;
       }
     }
   }
   ```

   Publish the rules.

5. **Now download the config.** Click **Settings** in the left menu and choose
   **General** — that page is what older guides call *Project settings*. Scroll
   to *Your apps*, select the Android app and download
   **`google-services.json`**.

   Open the file in any text editor and check it contains a line with
   `"client_type": 3`. If it does not, step 3 did not take — go back, make
   sure the Google provider really is enabled, and download again.

6. **Add it to the project.** Put the file at `app/google-services.json`,
   then commit and push:

   ```bash
   git add app/google-services.json
   git commit -m "Add Firebase configuration"
   git push
   ```

   `app/build.gradle.kts` applies the google-services plugin only when that
   file exists, so this is the switch that turns the whole feature on. The
   next CI build produces an APK with working sign in.

7. **Install and try it.** Open the new APK, go to *Settings → Account* and
   tap *Sign in with Google*. The account chooser appears, and after picking
   an account the card shows your name and the time of the last backup.

## If sign in does not work

| What you see | What it means |
| --- | --- |
| "Cloud backup is not set up in this build" | The APK was built without `google-services.json`, or the file has no `"client_type": 3` entry. Redo steps 3 and 5. |
| The account chooser opens, then nothing happens | The SHA-1 registered in Firebase does not match the APK's signing key. Check the fingerprint under *Settings → General → Your apps*. |
| "Backup failed: PERMISSION_DENIED" | The Firestore rules from step 4 were not published. |
| "Backup failed" mentioning an unavailable database | Firestore was never created. Do step 4. |

## What actually gets backed up

One Firestore document per student, at `users/{uid}`, holding a single JSON
payload:

- the whole profile: XP, level, streak, best streak, total minutes, daily
  goal, badges and languages tried;
- per book: progress, last page, minutes read, favourite and finished flags,
  and all flashcards.

Book **files** are not uploaded. They can be large and they are already on the
device, so only the reading history travels. Restoring works like this: sign in
on the new device, add your files again, and each book picks its progress and
flashcards back up as it is imported — matched on title and format.

Merging never loses progress. Both sides are compared field by field and the
further-along value wins, so reading offline on two devices and syncing later
ends with the best of both rather than one overwriting the other.

## Is google-services.json a secret?

No. It ships inside every copy of the APK, so anyone who has the app has it.
It identifies the project; it does not grant access to anything. What actually
protects each student's data is the Firestore rule in step 4, which ties every
document to the signed in account. Committing the file to the repository is
the normal thing to do.
