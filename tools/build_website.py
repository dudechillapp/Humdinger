"""Builds the pages in website/ (privacy policy, house rules, account deletion).

Edit the text below and run:  python tools/build_website.py
Then publish with:              firebase deploy --only hosting
"""
from pathlib import Path

CONTACT = "humdinger.application@gmail.com"
OWNER = "Christopher Kowalski"
UPDATED = "25 September 2026"
MIN_AGE = 16  # keep in sync with HouseRules.MIN_AGE in the app

HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<link rel="icon" href="/icon.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@600&family=Nunito:wght@400;700&display=swap" rel="stylesheet">
<link rel="stylesheet" href="/style.css">
</head>
<body>
<main>
<header class="brand"><a href="/"><img src="/icon.png" alt=""><span>Humdinger</span></a></header>
"""

FOOT = f"""
<footer>
<p><a href="/rules">House rules</a> · <a href="/privacy">Privacy policy</a> · <a href="/delete-account">Delete your account</a> · <a href="mailto:{CONTACT}">{CONTACT}</a></p>
</footer>
</main>
</body>
</html>
"""


def page(name, title, description, body):
    html = HEAD.format(title=title, description=description) + body + FOOT
    Path(__file__).resolve().parent.parent.joinpath("website", name).write_text(html, encoding="utf-8")


# ---------------------------------------------------------------- Home
page("index.html", "Humdinger: hum it, and the crowd names it",
     "Got a song stuck in your head? Hum it, sing it or mime it, and the Humdinger community will name that tune.",
     """
<h1>Got a song stuck in your head?</h1>
<p>Hum it, sing it, beatbox it or mime along to the words you think it has. The Humdinger crowd of music boffins will name that tune.</p>
<div class="card">
<p><strong>👑 Name a song:</strong> +20 points<br>
<strong>🙋 Have a go before it's solved:</strong> +5 points<br>
<strong>🤝 No AI:</strong> just real people having a laugh together.</p>
</div>
<p class="muted">Coming soon to Google Play.</p>
<div class="links">
<a href="/rules">📜 House rules</a>
<a href="/privacy">🔒 Privacy policy</a>
<a href="/delete-account">🗑️ Delete your account</a>
</div>
""")

# ---------------------------------------------------------------- House rules
RULES = [
    ("😂", "Laugh with people, not at them", "Everyone's brave for humming in public. Cheer them on. No bullying, mocking people's looks or voices, or harassment."),
    ("🚫", "No hate", "Nothing that attacks people for who they are: race, religion, gender, sexuality, disability, where they're from, or anything else."),
    ("🧼", "Keep it clean", "No nudity, sexual content, violence, drugs or anything illegal, in clips, guesses, comments, replies or messages. Comments are for cheering people on or a bit of fun, never for putting someone down."),
    ("🙋", "Only film yourself", "Don't record other people without their permission, and never share anyone's personal details, like phone numbers or addresses. That includes your own."),
    ("🎯", "Real guesses only", "Guesses are honest attempts with a link to the song. No spam, ads, self-promotion or links to anything else."),
    ("🎭", "Be yourself", "Don't pretend to be someone else, including the Humdinger team."),
    ("🎂", f"{MIN_AGE} and over", f"You need to be at least {MIN_AGE} to use Humdinger."),
]
rules_html = "\n".join(
    f'<div class="card rule"><div class="emoji">{e}</div><div><h3>{t}</h3><p>{b}</p></div></div>' for e, t, b in RULES
)
page("rules.html", "House rules · Humdinger", "The rules everyone agrees to before joining in on Humdinger.", f"""
<h1>House rules</h1>
<p>Humdinger is all about having a laugh together. Everyone agrees to these rules before they post, guess or chat.</p>
{rules_html}
<h2>If someone breaks the rules</h2>
<p>Every clip, guess, reply and conversation can be reported from its ⋮ menu, and you can block anyone. Blocked people disappear from your feed, can't guess on your requests and can't message you. They aren't told.</p>
<p>A clip reported by three different people (from accounts at least a day old) is removed automatically, and its owner is told why. We also review reports ourselves, and we can remove anything, or any account, that breaks these rules.</p>
<p>To report something urgent, or anything we should know about, email <a href="mailto:{CONTACT}">{CONTACT}</a>.</p>
<h2>Copyright</h2>
<p>Humming or singing a few seconds of a song to find out what it's called is what Humdinger is for. If you own the rights to something posted on Humdinger and want it taken down, report it in the app with "Copyright problem", or email <a href="mailto:{CONTACT}?subject=Copyright%20request">{CONTACT}</a> with what it is, where it appears in the app (the name of the person who posted it and roughly when), and how we can reach you. We'll remove content that infringes your rights.</p>
<p class="muted">Last updated {UPDATED}.</p>
""")

# ---------------------------------------------------------------- Delete account
page("delete-account.html", "Delete your account · Humdinger",
     "How to delete your Humdinger account and the data that goes with it.", f"""
<h1>Delete your Humdinger account</h1>
<h2>In the app (quickest)</h2>
<div class="card">
<p>1. Open Humdinger and go to the <strong>Me</strong> tab.<br>
2. Scroll down to <strong>🛡️ Safety and privacy</strong>.<br>
3. Tap <strong>🗑️ Delete my account</strong>, then <strong>Delete for good</strong>.</p>
<p>Everything is deleted straight away.</p>
</div>
<h2>Without the app</h2>
<p>If you can't use the app any more (for example, you uninstalled it), email <a href="mailto:{CONTACT}?subject=Delete%20my%20Humdinger%20account">{CONTACT}</a> with the subject "Delete my Humdinger account". Tell us your Humdinger name. If you saved your account with Google, send the email from that Google address so we can check it's you. We'll delete your account within 30 days and email you when it's done.</p>
<h2>What gets deleted</h2>
<ul>
<li>Your profile: your name, points, tier, settings and your list of songs you've named.</li>
<li>Your requests and recordings, along with every guess, reaction and report on them.</li>
<li>Your guesses and comments on other people's requests that haven't been solved yet, and all your replies under guesses and comments on hums.</li>
<li>Your friends list (you're also taken off your friends' lists) and any friend requests to or from you.</li>
<li>Your thank-yous and chats, including chats with friends (for both people in the conversation), and recordings.</li>
<li>Your reactions, your blocked list and any notices for you.</li>
<li>Your sign-in account, including the link to your Google account if you saved one.</li>
</ul>
<h2>What's kept</h2>
<ul>
<li>If you named or guessed a song that was then solved, your guess stays on that request as "Deleted user", without your comment, so the other person's solved song still makes sense. It's deleted when that request is deleted (at most 3 months after it was posted).</li>
<li>Points you helped other people earn stay with them.</li>
<li>Records of reports and removals, which don't include your name, are kept for up to 12 months to keep the community safe.</li>
</ul>
""")

# ---------------------------------------------------------------- Privacy policy
ROWS = [
    ("Account ID", "A random ID made when you first open the app. It isn't linked to your real identity.", "Until you delete your account"),
    ("Google account (optional)", "If you choose to save your progress with Google: your email address, name and profile photo link from Google. Only used for signing in. Never shown to anyone.", "Until you delete your account"),
    ("Your Humdinger name", "The fun name you're given or pick yourself. Everyone can see it.", "Until you delete your account"),
    ("Points and songs named", "Your points, tier and a record of the songs you named (title, artist, link, your comment, the requester's name and clue).", "Until you delete your account"),
    ("Recordings", "Audio or video clips you post, which may include your voice and face, and the clue you add.", "The time you choose (1 day to 3 months), then deleted"),
    ("Guesses, comments, replies and reactions", "Song title, artist, link and optional comment, comments on hums, short replies under guesses, and the emoji reactions you give.", "Deleted with the request they're on (you can delete your own comments and replies any time)"),
    ("Friends", "Who you're friends with, and friend requests you send or receive.", "Until either of you unfriends, blocks, or deletes their account"),
    ("Settings", "Which notifications you get, quiet hours and daily summary times (with your phone's time zone, so they happen at your local time), and your privacy choices (who can send you friend requests, message you or comment on your hums, and whether you're on the leaderboard). Your colour, autoplay and feed choices stay on your phone.", "Until you delete your account"),
    ("Thank-yous and messages", "Written or recorded thank-yous, and chat messages (including chats with friends). Only the two people in the conversation can see them.", "Recordings: the sender's chosen time. Messages: until either person deletes their account"),
    ("Blocks and reports", "Who you've blocked (only you can see this), and reports you make with the reason.", "Blocks: until you unblock or delete your account. Reports: up to 12 months"),
    ("Settings", "How long to keep your recordings, and which version of the house rules you agreed to.", "Until you delete your account"),
    ("Notification address", "A token from Google that lets us send notifications to your phone.", "Until you sign out or delete your account"),
    ("Technical data", "Google's servers process your IP address and basic device information to deliver the app and keep it secure.", "Short periods set by Google"),
]
rows_html = "\n".join(f"<tr><td><strong>{a}</strong></td><td>{b}</td><td>{c}</td></tr>" for a, b, c in ROWS)

page("privacy.html", "Privacy policy · Humdinger", "What Humdinger collects, why, how long it's kept and your rights.", f"""
<h1>Privacy policy</h1>
<p class="muted">Last updated {UPDATED}</p>
<p>Humdinger is an app where people hum, sing or mime a song they can't name, and other people name it. This policy explains what data the app uses, why, and what you can do about it. We've kept it as plain as we can.</p>

<h2>Who we are</h2>
<p>Humdinger is run by {OWNER}, who is responsible for your data (the "controller" under the EU's General Data Protection Regulation, or GDPR). Contact: <a href="mailto:{CONTACT}">{CONTACT}</a>.</p>

<h2>The short version</h2>
<ul>
<li>You don't have to sign up or give us your email. You start as a guest with a random fun name.</li>
<li>Your clips are deleted automatically after the time you choose, at most 3 months.</li>
<li>No adverts, no analytics or tracking, and we never sell your data.</li>
<li>You can delete your account and data at any time, right in the app.</li>
</ul>

<h2>What we collect and how long we keep it</h2>
<div class="table-wrap"><table>
<tr><th>What</th><th>Details</th><th>Kept for</th></tr>
{rows_html}
</table></div>

<h2>Microphone, camera and notifications</h2>
<p>The app asks to use your microphone and camera only so you can record a clip or a thank-you. It only records while you're recording, and nothing leaves your phone until you tap to post it. Notifications (for example new guesses, comments, replies, your guess being crowned, thank-yous, messages, friend requests, friends' new hums and removed clips) are only sent if you allow them. You can choose exactly which ones you get in the app (Me → Settings), or turn them all off in your phone's settings.</p>

<h2>Who can see what</h2>
<p>Everyone using Humdinger can see your name, tier, points, and the clips, clues, guesses, comments, replies and reactions you post. Your thank-yous and chats can only be seen by the two people in the conversation. Your friends list, blocked list, reports and notification settings are private. We don't look at conversations unless one is reported to us, and then only to deal with the report.</p>

<h2>Why we use your data (legal bases)</h2>
<ul>
<li><strong>To run the app for you</strong> (GDPR Article 6(1)(b), performing our agreement with you): your account, name, points, clips, guesses, messages and settings.</li>
<li><strong>To keep Humdinger safe</strong> (GDPR Article 6(1)(f), legitimate interests): reports, automatic removal of reported clips, blocking, moderation records and security.</li>
<li><strong>With your permission</strong> (GDPR Article 6(1)(a), consent): using the microphone and camera, and sending notifications. You can withdraw permission in your phone's settings at any time.</li>
</ul>

<h2>Who helps us</h2>
<p>Humdinger runs on Google Firebase, provided by Google Ireland Limited and Google LLC. Google stores and processes the data for us, under Google's data processing terms, on servers in the European Union and the United States (recordings are stored in the United States). Transfers to the United States are covered by the EU-U.S. Data Privacy Framework, which Google LLC is certified under, and by the European Commission's Standard Contractual Clauses. Links you add open in the music service you choose (for example YouTube or Spotify), whose own privacy policy then applies. We don't share your data with anyone else, unless the law requires us to.</p>

<h2>Your rights</h2>
<p>You have the right to access your data, correct it, delete it, restrict or object to how we use it, and receive it in a portable format. You can change your name and your recording settings, and delete your account, right in the app (Me tab). For anything else, email <a href="mailto:{CONTACT}">{CONTACT}</a> and we'll reply within one month. You can also complain to a data protection authority, for example the one where you live, or ours: the Landesbeauftragte für Datenschutz und Informationsfreiheit Nordrhein-Westfalen (LDI NRW), Germany.</p>

<h2>Deleting your account</h2>
<p>In the app: Me tab → Safety and privacy → Delete my account. It deletes your profile, clips, guesses, messages and everything else listed on our <a href="/delete-account">account deletion page</a>, which also explains how to ask for deletion if you can't use the app. If you're a guest and lose your phone or uninstall the app, you lose access to your account. Email us with your Humdinger name and we'll delete it.</p>

<h2>Age</h2>
<p>Humdinger is for people aged {MIN_AGE} and over. If you think someone younger is using it, please tell us and we'll delete their account.</p>

<h2>Changes</h2>
<p>If we change this policy, we'll update the date at the top. If a change matters, we'll tell you in the app.</p>
""")

page("404.html", "Not found · Humdinger", "Page not found.", """
<h1>🦗 Nothing here</h1>
<p>That page doesn't exist. <a href="/">Go to the home page</a>.</p>
""")
print("Website built.")
