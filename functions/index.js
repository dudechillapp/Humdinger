/**
 * Humdinger server jobs (Firebase Cloud Functions).
 *
 * 1. Auto-moderation: when enough different people report a request, it is deleted
 *    for good: the clip, the request, its guesses, reactions and reports. The owner gets
 *    a notice in their Messages saying so, with a summary of what the reports were about.
 * 2. Expiry: every request and thank-you has an `expireAt` time, set from its owner's
 *    "Keep my recordings for" setting (up to 3 months). Every 30 minutes, expired ones
 *    are deleted the same way.
 *
 * 3. Push notifications: new guesses, comments and replies, your guess was crowned, thank-yous,
 *    chat messages, friend requests, friends' new hums (and, if someone asks for it, every new hum),
 *    and removed requests. Each person chooses which ones they get (Settings in the app).
 *
 * 5. Storage allowance guard (checkStorageBudget): every hour, checks how much of this
 *    month's free Cloud Storage allowance has been used. Close to the limit, videos pause
 *    (hums only); nearly at it, all new recordings pause until next month.
 *
 * 4. Account deletion: "Delete my account" in the app (deleteAccount), or a request you
 *    create in the Firebase console (deletionRequests/<their id>), deletes everything
 *    about a person. See deleteUserData() for exactly what happens.
 *
 * Kept for good (never deleted by these jobs): points, and each person's record of the
 * songs they've named (users/<id>/solved), with their answer and comment.
 *
 * Deploy with:  firebase deploy --only functions   (see SETUP.md)
 */
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { getMessaging } = require("firebase-admin/messaging");
const { getAuth } = require("firebase-admin/auth");
const { MetricServiceClient } = require("@google-cloud/monitoring");

initializeApp();
setGlobalOptions({ region: "europe-west1", maxInstances: 5 });

/** How many different people must report a request before it's removed. */
const REPORTS_TO_REMOVE = 3;

/**
 * Only reports from accounts at least this old count towards auto-removal, so one person
 * can't make a few fresh guest accounts and delete someone else's clip. (Every report is
 * still kept until the request is removed.)
 */
const MIN_ACCOUNT_AGE_HOURS = 24;

/** Points for naming a song (keep in sync with Points.SOLVER in the app), used in the notification. */
const SOLVER_POINTS = 20;

/** How long removal notices stay in someone's Messages. */
const NOTICE_DAYS = 30;

/** How long moderation records (reports, removals) are kept. Keep in sync with the privacy policy. */
const MODERATION_RECORD_DAYS = 365;

/** What someone's name becomes on things that stay after they delete their account. */
const DELETED_ID = "deleted";
const DELETED_NAME = "Deleted user";

/**
 * Which notifications people get unless they change it in Settings.
 * Keep in sync with NotifyType in the app (data/NotificationSettings.kt).
 */
const NOTIFY_DEFAULTS = {
  guesses: true,
  comments: true,
  replies: true,
  crowned: true,
  thanks: true,
  chat: true,
  friendRequests: true,
  friendHums: true,
  allHums: false,
  removed: true,
};

/** Whether this person wants this kind of notification (their Settings). */
function wantsNotification(user, kind) {
  const prefs = user.get("notify") || {};
  if (prefs.all === false) return false;
  if (!kind) return true;
  const choice = prefs[kind];
  return typeof choice === "boolean" ? choice : NOTIFY_DEFAULTS[kind] !== false;
}

/** An hour 0-23 from their settings, or the fallback. */
function hourSetting(value, fallback) {
  return Number.isInteger(value) && value >= 0 && value <= 23 ? value : fallback;
}

/** The hour (0-23) right now in their time zone (their phone's, saved with their settings). */
function localHour(tz) {
  try {
    return Number(new Intl.DateTimeFormat("en-GB", {
      hour: "numeric", hourCycle: "h23", timeZone: tz || "Europe/Berlin",
    }).format(new Date()));
  } catch (e) {
    return new Date().getUTCHours();
  }
}

/** True during their quiet hours: notifications still arrive, but silently. */
function inQuietHours(prefs) {
  if (prefs.quietOn !== true) return false;
  const start = hourSetting(prefs.quietStart, 22);
  const end = hourSetting(prefs.quietEnd, 8);
  const hour = localHour(prefs.tz);
  if (start === end) return false;
  return start < end ? hour >= start && hour < end : hour >= start || hour < end;
}

/** With the daily summary on, these still come straight away (they're personal messages). */
const ALWAYS_INSTANT = new Set(["chat", "thanks"]);

/** How each kind is described in the daily summary. */
const DIGEST_LABELS = {
  guesses: ["🎯", "new guess", "new guesses"],
  comments: ["💬", "comment", "comments"],
  replies: ["↩️", "reply", "replies"],
  crowned: ["👑", "song you named", "songs you named"],
  friendRequests: ["👋", "friend update", "friend updates"],
  friendHums: ["🎤", "new hum from a friend", "new hums from friends"],
  allHums: ["📣", "new hum", "new hums"],
  removed: ["⚠️", "removed clip", "removed clips"],
};

/** Sends a push notification to every phone signed in to this account. */
async function sendPush(user, title, body, open, quiet) {
  const tokens = (user.get("fcmTokens") || []).filter(Boolean);
  if (tokens.length === 0) return;

  const response = await getMessaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    data: { open: open || "", quiet: quiet ? "1" : "0" },
    android: {
      priority: quiet ? "normal" : "high",
      notification: {
        channelId: quiet ? "quiet" : "activity",
        color: "#FF5A36",
        icon: "ic_notification",
      },
    },
  });

  // Forget addresses of phones that uninstalled the app or reset.
  const dead = [];
  response.responses.forEach((r, i) => {
    const code = r.error && r.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
      dead.push(tokens[i]);
    }
  });
  if (dead.length) await user.ref.update({ fcmTokens: FieldValue.arrayRemove(...dead) });
}

/**
 * Notifies someone, following their Settings: only kinds they want, silently during quiet
 * hours, or saved up for their daily summary.
 * `open` tells the app where to go when it's tapped: "request:<id>", "chat:<id>", "friends" or "messages".
 */
async function notifyUser(uid, title, body, open, kind) {
  if (!uid || uid === DELETED_ID) return;
  const db = getFirestore();
  const user = await db.doc(`users/${uid}`).get();
  if (!user.exists || !wantsNotification(user, kind)) return;
  const prefs = user.get("notify") || {};

  if (prefs.daily === true && kind && !ALWAYS_INSTANT.has(kind)) {
    await user.ref.update({ [`digest.${kind}`]: FieldValue.increment(1) });
    return;
  }
  await sendPush(user, title, body, open, inQuietHours(prefs));
}

/** Every hour: send daily summaries to people whose chosen hour it is (their time). */
exports.sendDailySummaries = onSchedule({ schedule: "every 1 hours", timeZone: "Europe/Berlin" }, async () => {
  const db = getFirestore();
  await forEachDoc(db.collection("users").where("notify.daily", "==", true), async (user) => {
    const prefs = user.get("notify") || {};
    if (localHour(prefs.tz) !== hourSetting(prefs.digestHour, 18)) return;
    const digest = user.get("digest") || {};
    const parts = Object.entries(DIGEST_LABELS)
      .filter(([kind]) => (digest[kind] || 0) > 0)
      .map(([kind, [emoji, one, many]]) => `${emoji} ${digest[kind]} ${digest[kind] === 1 ? one : many}`);
    if (parts.length === 0) return;
    await user.ref.update({ digest: FieldValue.delete() });
    if (prefs.all === false) return;
    await sendPush(user, "🎶 Your Humdinger day", parts.join(" · "), "feed", false);
  });
});

/** Deletes a request completely: its clip file(s), the request and everything under it. */
async function deleteRequestCompletely(requestRef) {
  const snap = await requestRef.get();
  if (!snap.exists) return null;
  const ownerId = snap.get("ownerId");
  if (ownerId) {
    // Clips live at clips/<ownerId>/<requestId>/... (older test clips: clips/<ownerId>/<requestId>.m4a)
    try {
      await getStorage().bucket().deleteFiles({ prefix: `clips/${ownerId}/${requestRef.id}` });
    } catch (e) {
      logger.warn(`Couldn't delete clip files for ${requestRef.id}`, e);
    }
  }
  await getFirestore().recursiveDelete(requestRef);
  return snap;
}

/** Runs whenever someone reports a request (one report per person). */
exports.onReport = onDocumentCreated("requests/{requestId}/reports/{reporterId}", async (event) => {
  const db = getFirestore();
  const { requestId } = event.params;
  const reports = await db.collection(`requests/${requestId}/reports`).get();

  const cutoff = Date.now() - MIN_ACCOUNT_AGE_HOURS * 60 * 60 * 1000;
  let trustedReports = 0;
  for (const report of reports.docs) {
    const reporter = await db.doc(`users/${report.id}`).get();
    const createdAt = reporter.get("createdAt");
    if (createdAt && createdAt.toMillis() <= cutoff) trustedReports++;
  }
  logger.info(`Request ${requestId}: ${reports.size} reports, ${trustedReports} count towards removal`);

  if (trustedReports >= REPORTS_TO_REMOVE) {
    const removed = await deleteRequestCompletely(db.doc(`requests/${requestId}`));
    if (removed) {
      // A record for you, visible in the Firebase console (Firestore → moderationLog).
      await db.collection("moderationLog").add({
        requestId,
        ownerId: removed.get("ownerId") || null,
        ownerName: removed.get("ownerName") || null,
        reasons: reports.docs.map((r) => r.get("reason") || "").filter(Boolean),
        reportCount: reports.size,
        removedAt: FieldValue.serverTimestamp(),
      });
      logger.info(`Removed request ${requestId} after ${trustedReports} reports`);

      // Tell the owner, with a summary of what the reports were about. It shows in their Messages.
      const ownerId = removed.get("ownerId");
      if (ownerId) {
        const counts = {};
        for (const r of reports.docs) {
          const reason = r.get("reason") || "Something else";
          counts[reason] = (counts[reason] || 0) + 1;
        }
        const reasonSummary = Object.entries(counts)
          .sort((a, b) => b[1] - a[1])
          .map(([reason, n]) => (n > 1 ? `${reason} (${n} people)` : reason))
          .join(", ");
        await db.collection("notices").add({
          toUid: ownerId,
          kind: "removed",
          mediaType: removed.get("mediaType") || "audio",
          hint: removed.get("hint") || "",
          postedAt: removed.get("createdAt") || null,
          reportCount: reports.size,
          reasonSummary,
          read: false,
          createdAt: FieldValue.serverTimestamp(),
          expireAt: Timestamp.fromMillis(Date.now() + NOTICE_DAYS * 24 * 60 * 60 * 1000),
        });
        await notifyUser(
          ownerId,
          "⚠️ Your request was removed",
          `It was reported by ${reports.size} people. Tap to see why.`,
          "messages",
          "removed",
        );
      }
    }
  }
});

/** Every 30 minutes: delete requests whose time is up. */
exports.deleteExpiredClips = onSchedule(
  { schedule: "every 30 minutes", timeZone: "Europe/Berlin" },
  async () => {
    const db = getFirestore();
    const expired = await db
      .collection("requests")
      .where("expireAt", "<=", Timestamp.now())
      .limit(200)
      .get();
    for (const doc of expired.docs) {
      await deleteRequestCompletely(doc.ref);
    }
    if (!expired.empty) logger.info(`Deleted ${expired.size} expired requests`);

    // Recorded thank-yous expire too (using the sender's "keep my recordings for" setting).
    // Only the recording is deleted; the conversation it started stays.
    const expiredThanks = await db
      .collection("thanks")
      .where("expireAt", "<=", Timestamp.now())
      .limit(200)
      .get();
    for (const doc of expiredThanks.docs) {
      const fromUid = doc.get("fromUid");
      if (fromUid) {
        try {
          await getStorage().bucket().deleteFiles({ prefix: `thanks/${fromUid}/${doc.id}/` });
        } catch (e) {
          logger.warn(`Couldn't delete thank-you files for ${doc.id}`, e);
        }
      }
      await doc.ref.update({ mediaUrl: "", mediaExpired: true, expireAt: FieldValue.delete() });
    }
    if (!expiredThanks.empty) logger.info(`Deleted ${expiredThanks.size} expired thank-you recordings`);

    // Old removal notices.
    const expiredNotices = await db.collection("notices").where("expireAt", "<=", Timestamp.now()).limit(200).get();
    for (const doc of expiredNotices.docs) await doc.ref.delete();

    // Old moderation records (see MODERATION_RECORD_DAYS).
    const cutoff = Timestamp.fromMillis(Date.now() - MODERATION_RECORD_DAYS * 24 * 60 * 60 * 1000);
    const oldRecords = [
      db.collection("moderationLog").where("removedAt", "<=", cutoff).limit(200),
      db.collection("chatReports").where("createdAt", "<=", cutoff).limit(200),
      db.collection("guessReports").where("createdAt", "<=", cutoff).limit(200),
      db.collection("userReports").where("createdAt", "<=", cutoff).limit(200),
    ];
    for (const query of oldRecords) {
      const snap = await query.get();
      for (const doc of snap.docs) await doc.ref.delete();
    }
  },
);

/** 🎯 Someone guessed on your request. */
exports.notifyNewGuess = onDocumentCreated("requests/{requestId}/guesses/{guessId}", async (event) => {
  const guess = event.data;
  if (!guess) return;
  const { requestId } = event.params;
  const request = await getFirestore().doc(`requests/${requestId}`).get();
  const ownerId = request.get("ownerId");
  if (!ownerId || ownerId === guess.get("authorId")) return;
  const who = guess.get("authorName") || "Someone";
  await notifyUser(
    ownerId,
    "🎯 New guess on your request!",
    `${who} thinks they know your song. Tap to check if they're right!`,
    `request:${requestId}`,
    "guesses",
  );
});

/** 💬 Someone replied under a guess: tell the guesser, and the person who asked. */
exports.notifyReply = onDocumentCreated("requests/{requestId}/replies/{replyId}", async (event) => {
  const reply = event.data;
  if (!reply) return;
  const { requestId } = event.params;
  const authorId = reply.get("authorId");
  const who = reply.get("authorName") || "Someone";
  const text = reply.get("text") || "";
  const preview = text.length > 100 ? text.slice(0, 97) + "…" : text;
  const guessAuthorId = reply.get("guessAuthorId");
  const request = await getFirestore().doc(`requests/${requestId}`).get();
  const ownerId = request.get("ownerId");

  if (guessAuthorId && guessAuthorId !== authorId && guessAuthorId !== DELETED_ID) {
    await notifyUser(guessAuthorId, `💬 ${who} replied to your guess`, preview, `request:${requestId}`, "replies");
  }
  if (ownerId && ownerId !== authorId && ownerId !== guessAuthorId) {
    await notifyUser(ownerId, `💬 ${who} replied on your request`, preview, `request:${requestId}`, "replies");
  }
});

/** 👑 Your guess was crowned (a record appears in users/<you>/solved). */
exports.notifyCrowned = onDocumentCreated("users/{uid}/solved/{requestId}", async (event) => {
  const record = event.data;
  if (!record) return;
  const { uid, requestId } = event.params;
  const song = record.get("songTitle") || "the song";
  const from = record.get("requesterName") || "Someone";
  await notifyUser(
    uid,
    `👑 You named it! +${SOLVER_POINTS} points`,
    `${from} crowned your answer "${song}". Legend!`,
    `request:${requestId}`,
    "crowned",
  );
});

/** ✉️ You got a thank-you (written or recorded). */
exports.notifyThanks = onDocumentCreated("thanks/{requestId}", async (event) => {
  const thanks = event.data;
  if (!thanks) return;
  if (thanks.get("mediaType") === "friend") return; // a friend chat, not a thank-you
  const from = thanks.get("fromName") || "Someone";
  const song = thanks.get("songTitle") || "their song";
  const note = thanks.get("note") || "";
  const body = thanks.get("mediaType") === "text" && note
    ? `${from}: "${note.slice(0, 100)}"`
    : `${from} recorded you a thank-you for naming "${song}".`;
  await notifyUser(thanks.get("toUid"), "✉️ You got a thank-you!", body, `chat:${event.params.requestId}`, "thanks");
});

/** 💬 New chat message. */
exports.notifyChatMessage = onDocumentCreated("thanks/{requestId}/messages/{messageId}", async (event) => {
  const msg = event.data;
  if (!msg) return;
  const { requestId } = event.params;
  const convo = await getFirestore().doc(`thanks/${requestId}`).get();
  if (!convo.exists || convo.get("blockedBy")) return;
  const senderId = msg.get("senderId");
  const toUid = (convo.get("members") || []).find((uid) => uid !== senderId);
  const senderName = senderId === convo.get("fromUid") ? convo.get("fromName") : convo.get("toName");
  const text = msg.get("text") || "";
  await notifyUser(
    toUid,
    `💬 ${senderName || "New message"}`,
    text.length > 120 ? text.slice(0, 117) + "…" : text,
    `chat:${requestId}`,
    "chat",
  );
});

/** 💬 Someone commented on your hum. */
exports.notifyComment = onDocumentCreated("requests/{requestId}/comments/{commentId}", async (event) => {
  const comment = event.data;
  if (!comment) return;
  const { requestId } = event.params;
  const request = await getFirestore().doc(`requests/${requestId}`).get();
  const ownerId = request.get("ownerId");
  if (!ownerId || ownerId === comment.get("authorId")) return;
  const who = comment.get("authorName") || "Someone";
  const text = comment.get("text") || "";
  await notifyUser(
    ownerId,
    `💬 ${who} commented on your ${request.get("mediaType") === "video" ? "mime" : "hum"}`,
    text.length > 100 ? text.slice(0, 97) + "…" : text,
    `request:${requestId}`,
    "comments",
  );
});

/** 👋 Someone wants to be your friend. */
exports.notifyFriendRequest = onDocumentCreated("friendRequests/{requestId}", async (event) => {
  const req = event.data;
  if (!req) return;
  await notifyUser(
    req.get("toUid"),
    `👋 ${req.get("fromName") || "Someone"} wants to be friends`,
    "Tap to accept or decline.",
    "friends",
    "friendRequests",
  );
});

/** 🎉 Your friend request was accepted (the accepter adds you to their list and them to yours). */
exports.notifyFriendAdded = onDocumentCreated("users/{uid}/friends/{friendUid}", async (event) => {
  const friend = event.data;
  if (!friend) return;
  const { uid, friendUid } = event.params;
  if (friend.get("addedBy") === uid) return; // this is the accepter's own list
  if (friend.get("addedBy") !== friendUid) return;
  await notifyUser(
    uid,
    `🎉 ${friend.get("name") || "Someone"} accepted your friend request`,
    "You're friends now. Say hi in Messages!",
    "friends",
    "friendRequests",
  );
});

/** True if either person has blocked the other. */
async function eitherBlocked(a, b) {
  const db = getFirestore();
  const [x, y] = await Promise.all([
    db.doc(`users/${a}/blocked/${b}`).get(),
    db.doc(`users/${b}/blocked/${a}`).get(),
  ]);
  return x.exists || y.exists;
}

/** Most people a single new hum notifies (friends first, then "every new hum" fans). */
const MAX_NEW_HUM_NOTIFICATIONS = 500;

/** 🎤 Someone posted a new hum: tell their friends, and anyone who wants every new hum. */
exports.notifyNewHum = onDocumentCreated("requests/{requestId}", async (event) => {
  const request = event.data;
  if (!request) return;
  const db = getFirestore();
  const { requestId } = event.params;
  const ownerId = request.get("ownerId");
  if (!ownerId) return;
  const who = request.get("ownerName") || "Someone";
  const verb = request.get("mediaType") === "video" ? "mime" : "hum";
  const hint = request.get("hint") || "";
  const body = hint ? `"${hint.slice(0, 100)}" Can you name it?` : "Can you name it?";
  const open = `request:${requestId}`;

  const done = new Set([ownerId]);
  const friends = await db.collection(`users/${ownerId}/friends`).limit(MAX_NEW_HUM_NOTIFICATIONS).get();
  for (const friend of friends.docs) {
    if (done.size > MAX_NEW_HUM_NOTIFICATIONS) break;
    done.add(friend.id);
    if (await eitherBlocked(ownerId, friend.id)) continue;
    await notifyUser(friend.id, `🎤 Your friend ${who} posted a new ${verb}`, body, open, "friendHums");
  }

  const fans = await db.collection("users").where("notify.allHums", "==", true)
    .limit(MAX_NEW_HUM_NOTIFICATIONS).get();
  for (const fan of fans.docs) {
    if (done.has(fan.id) || done.size > MAX_NEW_HUM_NOTIFICATIONS) continue;
    done.add(fan.id);
    if (await eitherBlocked(ownerId, fan.id)) continue;
    await notifyUser(fan.id, `🎤 ${who} posted a new ${verb}`, body, open, "allHums");
  }
});

// ---------------------------------------------------------------------------------------
// Account deletion
// ---------------------------------------------------------------------------------------

/** Runs `work` on every document a query finds, a page at a time. */
async function forEachDoc(query, work) {
  let snap = await query.limit(300).get();
  while (!snap.empty) {
    for (const doc of snap.docs) await work(doc);
    if (snap.size < 300) break;
    snap = await query.startAfter(snap.docs[snap.docs.length - 1]).limit(300).get();
  }
}

/**
 * Deletes everything about a person:
 *  - their requests, with the clips, guesses, reactions and reports on them
 *  - their replies and comments, their friends (on both lists) and friend requests, and their
 *    guesses on other people's open requests. On solved requests the guess stays
 *    (so the other person's solved song still makes sense) but shows "Deleted user" with no comment
 *  - their name on songs they solved, and their place in the list of people who got points
 *  - their thank-you and friend conversations and recordings (for both people)
 *  - their reactions and reports, notices, blocked list, songs-named record and profile
 *  - their name, so someone else can use it
 *  - their sign-in account (last)
 * Other people's points are not changed.
 */
async function deleteUserData(uid) {
  const db = getFirestore();
  const bucket = getStorage().bucket();
  const userRef = db.doc(`users/${uid}`);
  const user = await userRef.get();

  // 1. Their requests, and everything on them.
  await forEachDoc(db.collection("requests").where("ownerId", "==", uid), (doc) => deleteRequestCompletely(doc.ref));

  // 2. Their guesses on other people's requests.
  await forEachDoc(db.collectionGroup("guesses").where("authorId", "==", uid), async (guess) => {
    const requestRef = guess.ref.parent.parent;
    const request = await requestRef.get();
    if (request.exists && request.get("status") === "open") {
      // The replies under it go too.
      await forEachDoc(requestRef.collection("replies").where("guessId", "==", guess.id), (r) => r.ref.delete());
      await db.batch()
        .delete(guess.ref)
        .update(requestRef, { guessCount: FieldValue.increment(-1) })
        .commit();
    } else if (request.exists) {
      await guess.ref.update({ authorId: DELETED_ID, authorName: DELETED_NAME, comment: "" });
    } else {
      await guess.ref.delete();
    }
  });

  // 2b. Their replies under other people's guesses, and their comments on other people's hums.
  await forEachDoc(db.collectionGroup("replies").where("authorId", "==", uid), (doc) => doc.ref.delete());
  await forEachDoc(db.collectionGroup("comments").where("authorId", "==", uid), (doc) => doc.ref.delete());

  // 2c. Friends: off everyone's friends lists, and any friend requests to or from them.
  await forEachDoc(db.collectionGroup("friends").where("uid", "==", uid), (doc) => doc.ref.delete());
  await forEachDoc(db.collection("friendRequests").where("fromUid", "==", uid), (doc) => doc.ref.delete());
  await forEachDoc(db.collection("friendRequests").where("toUid", "==", uid), (doc) => doc.ref.delete());

  // 3. Songs they named for other people.
  await forEachDoc(db.collection("requests").where("solverId", "==", uid), (doc) =>
    doc.ref.update({ solverId: DELETED_ID, solverName: DELETED_NAME }));
  await forEachDoc(db.collection("requests").where("rewardedIds", "array-contains", uid), (doc) =>
    doc.ref.update({ rewardedIds: FieldValue.arrayRemove(uid) }));

  // 4. Thank-you conversations (and their recordings), for both people.
  await forEachDoc(db.collection("thanks").where("members", "array-contains", uid), async (convo) => {
    const fromUid = convo.get("fromUid");
    if (fromUid) {
      await bucket.deleteFiles({ prefix: `thanks/${fromUid}/${convo.id}/` }).catch((e) => logger.warn(e));
    }
    await db.recursiveDelete(convo.ref);
  });

  // 5. Their reactions (taking them off the counts) and reports.
  await forEachDoc(db.collectionGroup("reactions").where("uid", "==", uid), async (reaction) => {
    const requestRef = reaction.ref.parent.parent;
    const key = reaction.get("reaction");
    const batch = db.batch().delete(reaction.ref);
    const request = await requestRef.get();
    if (request.exists && key) batch.update(requestRef, { [`reactionCounts.${key}`]: FieldValue.increment(-1) });
    await batch.commit();
  });
  await forEachDoc(db.collectionGroup("reports").where("uid", "==", uid), (doc) => doc.ref.delete());

  // 6. Notices for them, and their name in other people's blocked lists.
  await forEachDoc(db.collection("notices").where("toUid", "==", uid), (doc) => doc.ref.delete());
  await forEachDoc(db.collectionGroup("blocked").where("uid", "==", uid), (doc) => doc.ref.delete());

  // 7. Any files left in their folders.
  await bucket.deleteFiles({ prefix: `clips/${uid}/` }).catch((e) => logger.warn(e));
  await bucket.deleteFiles({ prefix: `thanks/${uid}/` }).catch((e) => logger.warn(e));

  // 8. Their name (so it's free again) and profile, with songs named and blocked list.
  const nameKey = user.exists ? user.get("usernameLower") : null;
  if (nameKey) {
    const nameRef = db.doc(`usernames/${nameKey}`);
    const name = await nameRef.get();
    if (name.exists && name.get("uid") === uid) await nameRef.delete();
  }
  await db.recursiveDelete(userRef);

  // 9. Their sign-in account.
  await getAuth().deleteUser(uid).catch((e) => {
    if (e.code !== "auth/user-not-found") throw e;
  });
  logger.info(`Deleted account ${uid}`);
}

/** "Delete my account" in the app. */
exports.deleteAccount = onCall({ timeoutSeconds: 540, memory: "512MiB" }, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Please sign in first.");
  try {
    await deleteUserData(uid);
    return { deleted: true };
  } catch (e) {
    logger.error(`Deleting account ${uid} failed`, e);
    throw new HttpsError("internal", "Couldn't delete everything. Please try again.");
  }
});

/**
 * For deletion requests that come in by email: in the Firebase console, create a document
 * in the `deletionRequests` collection whose ID is the person's user ID (see SETUP.md).
 */
exports.onDeletionRequest = onDocumentCreated(
  { document: "deletionRequests/{uid}", timeoutSeconds: 540, memory: "512MiB" },
  async (event) => {
    const { uid } = event.params;
    try {
      await deleteUserData(uid);
      await event.data.ref.set({ status: "done", doneAt: FieldValue.serverTimestamp() }, { merge: true });
    } catch (e) {
      logger.error(`Deletion request for ${uid} failed`, e);
      await event.data.ref.set({ status: "failed", error: String(e.message || e) }, { merge: true });
    }
  },
);

// ---------------------------------------------------------------------------------------
// Storage allowance guard
// ---------------------------------------------------------------------------------------

/**
 * Google's free Cloud Storage allowance each month (the "Always Free" tier, for buckets in
 * US-EAST1, US-CENTRAL1 and US-WEST1): 5 GB stored, 100 GB downloaded, 5,000 "Class A"
 * operations (mostly uploads and file listings) and 50,000 "Class B" operations (mostly downloads).
 */
const FREE_ALLOWANCE = { storedGB: 5, downloadedGB: 100, classA: 5000, classB: 50000 };

/** Share of the allowance at which videos pause and only hums are accepted. */
const AUDIO_ONLY_AT = 0.7;

/** Share of the allowance at which all new recordings pause until next month. */
const PAUSE_AT = 0.9;

const GB = 1024 * 1024 * 1024;

function projectId() {
  try {
    return JSON.parse(process.env.FIREBASE_CONFIG).projectId;
  } catch (e) {
    return process.env.GCLOUD_PROJECT;
  }
}

function numberOf(value) {
  if (value == null) return 0;
  if (typeof value === "object" && typeof value.toNumber === "function") return value.toNumber();
  return Number(value) || 0;
}

function pointValue(point) {
  const v = point.value || {};
  return numberOf(v.int64Value != null ? v.int64Value : v.doubleValue);
}

/** Operations that Google counts as Class B (reads); deletes are free; everything else is Class A. */
function operationClass(method) {
  if (!method) return "A";
  if (/^Delete/.test(method)) return "free";
  if (/^(Get|Read|Head)/.test(method)) return "B";
  return "A";
}

exports.checkStorageBudget = onSchedule(
  { schedule: "every 60 minutes", timeZone: "Europe/Berlin" },
  async () => {
    const db = getFirestore();
    const client = new MetricServiceClient();
    const name = client.projectPath(projectId());
    const bucket = getStorage().bucket().name;
    const onBucket = `resource.type = "gcs_bucket" AND resource.labels.bucket_name = "${bucket}"`;

    const now = new Date();
    const monthStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
    const nowSec = Math.floor(now.getTime() / 1000);
    const monthInterval = { startTime: { seconds: Math.floor(monthStart.getTime() / 1000) }, endTime: { seconds: nowSec } };
    const hourlySums = { alignmentPeriod: { seconds: 3600 }, perSeriesAligner: "ALIGN_SUM" };

    // Stored now (Google measures this about once a day): the newest reading of each storage class.
    const [stored] = await client.listTimeSeries({
      name,
      filter: `metric.type = "storage.googleapis.com/storage/total_bytes" AND ${onBucket}`,
      interval: { startTime: { seconds: nowSec - 3 * 24 * 3600 }, endTime: { seconds: nowSec } },
      view: "FULL",
    });
    const storedBytes = stored.reduce((sum, series) => sum + (series.points.length ? pointValue(series.points[0]) : 0), 0);

    // Downloaded this month.
    const [sent] = await client.listTimeSeries({
      name,
      filter: `metric.type = "storage.googleapis.com/network/sent_bytes_count" AND ${onBucket}`,
      interval: monthInterval,
      aggregation: hourlySums,
      view: "FULL",
    });
    const downloadedBytes = sent.reduce((sum, series) => sum + series.points.reduce((s, p) => s + pointValue(p), 0), 0);

    // Operations this month, split into Class A and Class B.
    const [requests] = await client.listTimeSeries({
      name,
      filter: `metric.type = "storage.googleapis.com/api/request_count" AND ${onBucket}`,
      interval: monthInterval,
      aggregation: hourlySums,
      view: "FULL",
    });
    const ops = { A: 0, B: 0, free: 0 };
    for (const series of requests) {
      const method = series.metric && series.metric.labels ? series.metric.labels.method : null;
      ops[operationClass(method)] += series.points.reduce((s, p) => s + pointValue(p), 0);
    }

    const usage = {
      storedGB: storedBytes / GB,
      downloadedGB: downloadedBytes / GB,
      classA: ops.A,
      classB: ops.B,
    };
    const shares = Object.keys(FREE_ALLOWANCE).map((key) => ({ key, share: usage[key] / FREE_ALLOWANCE[key] }));
    const worst = shares.reduce((a, b) => (b.share > a.share ? b : a));

    let autoMode = "normal";
    if (worst.share >= PAUSE_AT) autoMode = "paused";
    else if (worst.share >= AUDIO_ONLY_AT) autoMode = "audioOnly";

    // You can force a mode by setting `override` on config/storage in the Firebase console
    // ("normal", "audioOnly" or "paused"). Delete the field to go back to automatic.
    const ref = db.doc("config/storage");
    const current = await ref.get();
    const override = current.exists ? current.get("override") : null;
    const mode = ["normal", "audioOnly", "paused"].includes(override) ? override : autoMode;

    if (!current.exists || current.get("mode") !== mode) {
      logger.warn(`Recording mode is now "${mode}" (${Math.round(worst.share * 100)}% of the free ${worst.key} allowance used)`);
    }
    await ref.set({
      mode,
      autoMode,
      usage: {
        storedGB: Number(usage.storedGB.toFixed(3)),
        downloadedGB: Number(usage.downloadedGB.toFixed(3)),
        classA: usage.classA,
        classB: usage.classB,
      },
      allowance: FREE_ALLOWANCE,
      percentUsed: Math.round(worst.share * 100),
      closestLimit: worst.key,
      checkedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
  },
);
