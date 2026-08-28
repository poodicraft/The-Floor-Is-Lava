# Turning on Google sign in and cloud backup

The app ships with the sign in screen, the account card and the whole backup
merge already written. What it cannot ship is a Google project — that has to
belong to you, because it is tied to your Google account and to this app's
signing fingerprint. Until you add one, the account card in Settings says
"Cloud backup is not set up in this build" and everything else works normally,
fully offline.

Setup takes about five minutes and is free.

## What you need

| Field | Value |
| --- | --- |
| Package name | `com.poodicraft.bookquest` |
| Debug signing SHA-1 | `16:D2:E4:C6:C9:2D:8C:5E:77:FB:12:42:C7:99:D6:59:69:39:32:1D` |

That SHA-1 comes from `keystore/debug.keystore`, which is committed to this
repository on purpose. Android's debug key is not a secret (its password is the
well known `android`), and pinning it means every APK — yours or CI's — is
signed with the same fingerprint, so Google sign in keeps working across
builds. You can re-derive it any time with:

```bash
keytool -list -v -keystore keystore/debug.keystore -storepass android -alias androiddebugkey
```

## Steps

1. Go to <https://console.firebase.google.com> and create a project. Google
   Analytics is not needed, so you can turn it off.
2. In the project, click **Add app → Android**.
   - Package name: `com.poodicraft.bookquest`
   - Debug signing certificate SHA-1: the value from the table above.
3. Download the **`google-services.json`** it offers you and put it at
   `app/google-services.json` in this repository.
4. In the Firebase console open **Build → Authentication → Get started**, and
   enable the **Google** provider.
5. Open **Build → Firestore Database → Create database**. Start in production
   mode, then replace the rules with these so each student can only read and
   write their own document:

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

6. Commit `app/google-services.json` and push. The build picks the file up
   automatically — `app/build.gradle.kts` only applies the google-services
   plugin when the file exists — and the next APK will have working sign in.

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
