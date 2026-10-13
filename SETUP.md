# Humdinger: setup guide

Humdinger is an Android app where people hum, sing or mime a song they can't name, and other people identify it. The requester crowns the right answer. The person who named the song gets **20 points**, and everyone else who had a go before then gets **5 points**.

Nobody has to sign up. On first launch, each person automatically becomes a guest with a fun random name like *Springy Toast* or *Lazy Frog*. They can change that name any time (type their own, or tap 🎲 for another random one). If they want to keep their progress, they can tap **Sign in with Google** on the **Me** tab later, and their name and points carry over.

The app is built with Kotlin and Jetpack Compose (Android's modern UI (user interface) toolkit). It uses Firebase (Google's app backend) for accounts, the database and clip storage.

---

## 1. Create the Firebase project (about 20 minutes)

1. Go to <https://console.firebase.google.com>, click **Create a project** and name it `Humdinger`. You can switch Google Analytics off.
2. **Add an Android app** (the Android icon on the project overview):
   - Android package name: `com.kowalski.humdinger` (it must match exactly)
   - App nickname: `Humdinger`
   - **SHA-1 certificate fingerprint:** Google sign-in needs this. To get it, open the project in Android Studio (step 2 below), open the **Terminal** tab at the bottom and run:
     ```
     .\gradlew signingReport
     ```
     If it complains that `JAVA_HOME` isn't set, open the **Gradle** panel on the right (elephant icon) → **Execute Gradle Task** → type `signingReport` and press Enter instead.
     Copy the `SHA1:` line under `Variant: debug` and paste it in. (The first sync will fail because `google-services.json` isn't there yet, but `signingReport` still works.) You can also add it later under **Project settings → Your apps → Add fingerprint**.

### Turn on the services

3. **Authentication** → Get started → **Sign-in method**, and enable these two:
   - **Anonymous**, which gives everyone an automatic guest account.
   - **Google**, for saving progress. Pick your email as the support email and click Save.
4. **Now** download **`google-services.json`** (Project settings → Your apps) and put it in the **`app`** folder:
   `J:\Android App Project\Humdinger\app\google-services.json`
   It has to be downloaded *after* Google sign-in is switched on and the SHA-1 is added, because that's when it gets the Google sign-in details. If you change either of those later, download it again.
5. **Firestore Database** → Create database → choose a location (e.g. `eur3 (europe-west)`) → start in **production mode**.
   - Open the **Rules** tab, delete everything, paste in the contents of `firebase/firestore.rules` and click **Publish**.
6. **Storage** (for the audio and video clips):
   - Since late 2024, Firebase requires the **Blaze (pay-as-you-go) plan** before it will create a Storage bucket. You'll need to add a billing card, but the free allowance still applies. Choose a **US region** such as `us-central1` to get the "Always Free" storage allowance (about 5 GB). A small test app will very likely cost nothing.
   - Straight after upgrading, go to **Google Cloud console → Billing → Budgets & alerts** and set a budget of about €5 with email alerts, so there are no surprises.
   - Create the bucket, open the **Rules** tab, paste in `firebase/storage.rules` and click **Publish**.

### Create the two database indexes

7. **Firestore Database → Indexes → Composite → Create index**, and create these two:

| Collection | Field 1 | Field 2 |
|---|---|---|
| `requests` | `status` Ascending | `createdAt` Descending |
| `requests` | `ownerId` Ascending | `createdAt` Descending |

And one more, so a name change can update someone's old guesses: **Indexes → Automatic → Add exemption**, set Collection ID to `guesses` and Field path to `authorId`. Under *Collection group* scope, tick **Ascending**, then save.

Each index takes a few minutes to build. If you forget one, the app shows a message in the feed, and **Logcat** in Android Studio shows an error with a link that creates the index for you in one click.

---

## 1b. Server jobs: auto-moderation and auto-delete (about 15 minutes, once)

Two things run on Firebase's servers rather than on phones (the code is in the `functions` folder):

- **Auto-moderation:** when **3 different people** report a request, it's deleted for good: the clip, the request, its guesses, reactions and reports. Only reports from accounts at least **24 hours old** count, so nobody can make a few fresh guest accounts to get someone's clip removed. Every removal is recorded in **Firestore → `moderationLog`** so you can see what happened. The person whose request was removed gets a notice in their **✉️ Messages** tab, with a summary of what it was reported for.
- **Auto-delete:** each person picks how long their recordings stay up on the Me tab (1 day, 3 days, 1 week, 1 month or 3 months; 1 week by default). Every 30 minutes, anything past its time is deleted with everything attached to it. They can also delete a request straight away from its ⋮ menu.

- **Kept for good:** points, and each person's list of **songs they've named** (with their answer and comment), shown on their Me tab. These stay even after the clips are gone.
- **Thank-yous and chat:** after crowning a guess, the requester can send **one** thank-you to the solver: a written message, or a recorded hum, song or video. It starts a conversation in both people's **✉️ Messages** tab where they can keep chatting. Either person can block (stops new messages) or report the conversation; reports land in Firestore → `chatReports`. A recorded thank-you is deleted with the sender's "keep my recordings for" setting, but the chat stays.

- **Notifications:** phones get a pop-up for a new guess on their request, their guess being crowned, a thank-you, or a removed request. Tapping it opens the right screen. The app asks for permission to notify the first time it opens (Android 13 and newer).

You can change the numbers at the top of `functions/index.js`.

**To put them live:**

1. Install **Node.js** (the "LTS" version) from <https://nodejs.org>, clicking Next through the installer.
2. In Android Studio, open the **Terminal** tab at the bottom (it opens in the Humdinger folder).
   - If a command below says *"running scripts is disabled on this system"*, click the small ⌄ arrow next to the terminal tab and choose **Command Prompt** instead of PowerShell, then carry on.
3. Run these one at a time (after the server code changes, always run `npm install` in the `functions` folder again before deploying):
   ```
   npm install -g firebase-tools
   firebase login
   cd functions
   npm install
   cd ..
   firebase deploy --only functions,firestore,storage,hosting
   ```
   - `firebase login` opens your browser. Sign in with the Google account that owns the Firebase project.
   - The last command also publishes the database rules, indexes, storage rules and the website (privacy policy, house rules and account deletion page) for you, so no more copy-pasting. If it asks whether to delete indexes that aren't in the file, answer **No**.
   - If it asks to enable APIs, say **yes**. If it asks how many days to keep container images, type **1**.
4. When it finishes you'll see ✔ next to each server job (`onReport`, `deleteExpiredClips`, `deleteAccount` and the notification jobs), and a **Hosting URL** (`https://humdinger-61b7b.web.app`). You can check them any time in the Firebase console under **Functions**.

Cost: Cloud Functions include 2 million free runs a month on the Blaze plan. Humdinger uses about 1,500 a month for the clean-up job plus one per report, so this should stay free.

After changing `firestore.rules`, `storage.rules`, `functions/index.js` or the website in future, just run the deploy command again.

## 1b-2. Staying within the free storage allowance

Clips live in Cloud Storage, which is free each month up to **5 GB stored, 100 GB downloaded, about 5,000 uploads and 50,000 downloads**. Every hour, the `checkStorageBudget` server job checks how much has been used this month and sets a mode that the app and the rules both follow:

| Used (of whichever allowance is closest) | What happens |
|---|---|
| Under 70% | Normal: hums and videos |
| 70% or more | **Hums only.** Videos pause, because a video clip is many times the size of a hum |
| 90% or more | **Recording pauses** until the allowance resets on the 1st of the month. Guessing, reactions, chat and written thank-yous still work |

People see a friendly note on the recording screen explaining why. Change the percentages at the top of the storage section in `functions/index.js`.

- **See the numbers:** Firestore → `config` → `storage` shows the current mode, usage and which allowance is closest.
- **Force a mode:** add a field `override` (text) to that document, set to `normal`, `audioOnly` or `paused`. It applies within the hour. Delete the field to go back to automatic.
- **When you deploy this the first time,** the command may ask to let Storage read the database ("cross-service rules"). Answer **Y**. It's how the storage rules check the mode.
- If the job's log (Firebase console → Functions → `checkStorageBudget` → Logs) shows a permission error, go to Google Cloud console → IAM, find the account ending `-compute@developer.gserviceaccount.com`, and give it the **Monitoring Viewer** role.

This only guards Storage. Existing clips can still be played while recording is paused, so keep your €5 budget alert too.

## 1c. Safety, privacy and account deletion (needed for Google Play)

**The website.** The `website` folder is a small site that Firebase hosts for free:

| Page | Address |
|---|---|
| Home | https://humdinger-61b7b.web.app |
| Privacy policy | https://humdinger-61b7b.web.app/privacy |
| House rules | https://humdinger-61b7b.web.app/rules |
| Delete your account | https://humdinger-61b7b.web.app/delete-account |

To change the wording, edit `tools/build_website.py`, run `python tools/build_website.py` in the Terminal, then deploy again. The app links to these pages from the Me tab. If you change the house rules, change `HouseRules` in `data/Safety.kt` to match. Raising `HouseRules.VERSION` makes everyone agree to them again.

*Not legal advice:* the privacy policy covers what the app actually does, but have a quick read to make sure you're happy with it. If you ever run Humdinger as a business (adverts, paid features), German law also expects a postal address on the site (an *Impressum*).

**House rules.** Everyone sees the rules once and taps **I agree** before they can use the app. It happens after the welcome message, and existing accounts see it on their next launch.

**Blocking.** Anyone can block someone from a request's ⋮ menu, a guess's ⋮ menu or a chat's ⋮ menu. Blocked people's requests, guesses and chats disappear for the blocker. The database rules also stop the blocked person guessing on the blocker's requests or messaging them. Unblock on the Me tab.

**Reports to check.** Every so often, look in Firestore at `guessReports` (reported guesses and comments), `chatReports` (reported chats) and `moderationLog` (clips removed automatically). Also check `userReports` (reported people, for example rude names: you can rename or delete the account with a `deletionRequests` document) and anything reported as "Copyright problem". Delete anything bad by hand. These records are deleted automatically after 12 months.

**Account deletion.** On the Me tab, **Delete my account** deletes everything straight away (the `deleteAccount` server job). If someone emails humdinger.application@gmail.com asking to be deleted:

1. In **Firestore**, open the `usernames` collection and find their name in lower case with `_` instead of spaces (e.g. *Lazy Frog* → `lazy_frog`). Copy the `uid` value.
2. Click **+ Start collection** (or open `deletionRequests` if it's there), name it `deletionRequests`, set the **Document ID** to the copied uid, add a field such as `requestedBy` = their email, and save.
3. Within a minute or so, the document shows `status: done`. Their account and data are gone. Reply to let them know.

## 1d. Google Play forms

Play Console asks some questions about the app. These answers match what Humdinger does. Check each one against Google's wording as you go.

- **Privacy policy:** `https://humdinger-61b7b.web.app/privacy`
- **App access:** all functionality is available without special access (no login needed).
- **Ads:** no ads.
- **Content rating:** answer honestly. Users can interact and share content (audio, video, chat), and there's no violence or gambling.
- **Target audience:** 16 and over (tick 16–17 and 18+ only), matching the house rules.
- **Data safety:**
  - *Collected:* Email address (optional, only with Google sign-in; account management), User IDs (app functionality), Voice or sound recordings (optional; app functionality), Videos (optional; app functionality), Other in-app messages (optional; app functionality), Other user-generated content: guesses and comments (optional; app functionality), Device or other IDs: the notification token (app functionality).
  - *Shared with third parties:* none. Google Firebase processes data on your behalf, which doesn't count as sharing.
  - *Encrypted in transit:* yes.
  - *Users can request deletion:* yes. **Delete account URL:** `https://humdinger-61b7b.web.app/delete-account`.


## 2. Open and run the app

1. Open **Android Studio** → **File → Open** → select the `Humdinger` folder (the one containing `settings.gradle.kts`).
2. Let Gradle sync. The first sync downloads a lot, so give it a few minutes.
   - If Android Studio offers to upgrade the Android Gradle Plugin, it's fine to accept.
   - If sync complains about a missing SDK (software development kit) platform, click the link it gives to install **Android 16 (API 36)**.
3. Plug in your phone with **USB (Universal Serial Bus) debugging** switched on (or start an emulator), then press **Run ▶**.
   - A real phone is best, because emulators have poor microphones and cameras.
   - Google sign-in needs Google Play services on the device, so use a phone or a "Google Play" emulator image.

## 3. Test it properly

The points only make sense with more than one person, so test with two guests:

1. Open the app on your phone. You're instantly a guest called something like *Wobbly Teapot*. Tap **Hum it** → record → **Post it**.
2. Open the app on an emulator or a second phone to get a second guest.
3. As the second guest: open the request, react with 😂, tap **I know this!** and paste a link to the song (YouTube, Spotify, Apple Music, Amazon Music, Deezer, SoundCloud, TIDAL, Shazam or song.link).
4. As the first guest: tap **👑 That's it!** on that guess. The second guest should get +20 on the leaderboard.
5. On the **Me** tab, tap **Sign in with Google**. Your name and points should stay the same, and it should now say "Saved to your Google account".

---

## How it's organised

```
app/src/main/java/com/kowalski/humdinger/
  MainActivity.kt           app entry; shows a splash while the guest account is set up
  SessionViewModel.kt       creates the guest account + fun name automatically
  data/
    Models.kt               data shapes, points values, reaction types
    FunNames.kt             the adjective + animal/object name lists (add your own!)
    GoogleSignIn.kt         the Google account picker
    Links.kt                YouTube/Spotify link checking
    UserRepository.kt       guest accounts, names, Google linking, leaderboard, settings
    RequestRepository.kt    posting clips, guesses, reactions, crowning + points, reports, expiry
    NameFilter.kt           the ban list for rude names
    Tiers.kt                tiers, level-up messages
    Safety.kt               blocking, house rules, reports of guesses, account deletion, website links
    Thanks.kt               thank-yous, chats and notices
  ui/
    HumdingerNavHost.kt     screens and the bottom tab bar
    feed/                   the "Who knows this?" / "Solved" feed
    record/                 recording audio (microphone) and video (CameraX)
    detail/                 one request: player, reactions, guesses, crown button
    leaderboard/            top namers
    profile/                your stats, "Save your progress", your requests, safety and privacy
    safety/                 house rules, block, report and delete-account dialogs
    inbox/                  the ✉️ Messages tab and chats
    theme/                  colours and fonts
functions/
  index.js                  server jobs: auto-moderation, deleting expired clips, notifications, account deletion
website/                    privacy policy, house rules and account deletion pages (built by tools/build_website.py)
firebase/
  firestore.rules           database security rules (they enforce the points system)
  storage.rules             clip upload rules
  firestore.indexes.json    the database indexes
```

**Changing the points:** edit `Points` in `data/Models.kt` **and** the matching numbers in `isAward()` in `firebase/firestore.rules`, then republish the rules.

**Adding names:** add words to the two lists in `data/FunNames.kt`. Keep them short and kind.

## Before putting it on the Play Store

- **Reporting and blocking** (required for apps where people post things): done. See 1c.
- **Moderation.** Requests with 3 reports are removed automatically (see 1b). Keep an eye on `moderationLog`, `guessReports` and `chatReports`, and remove anything bad that slips through.
- **Privacy policy, house rules and account deletion:** done. See 1c and 1d.
- **Closed test.** New personal developer accounts must run a closed test with at least 12 testers for 14 days in a row before they can publish to everyone.
- **Release SHA-1.** When you publish, add the Play Store's app-signing SHA-1 (shown in Play Console → App integrity) to Firebase too, or Google sign-in won't work for real users.
- **Package name.** `com.kowalski.humdinger` is permanent once published, so change it now if you want something else. Change it in `app/build.gradle.kts` and in Firebase.
