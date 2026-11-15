# Google Cloud setup (YouTube APIs + OAuth)

TubePilot uses your own Google Cloud project. Nothing is shared with the developer of the app.

## 1. Create the project and enable the APIs

1. Open <https://console.cloud.google.com/> → project picker → **New project** (for example `tubepilot-mychannel`).
2. **APIs & Services → Library**, enable:
   - **YouTube Data API v3**
   - **YouTube Analytics API**

## 2. Configure the OAuth consent screen

**APIs & Services → OAuth consent screen** (shown as "Google Auth Platform" in newer consoles):

1. User type **External** (or Internal for a Workspace organisation).
2. App name `TubePilot`, support e-mail, developer contact.
3. **Scopes / Data access → Add scopes:**
   - `https://www.googleapis.com/auth/youtube.upload`
   - `https://www.googleapis.com/auth/youtube.force-ssl`
   - `https://www.googleapis.com/auth/yt-analytics.readonly`
4. While the app is in **Testing**, add the Google account that owns your channel under **Test users**. Test-mode
   authorisations expire after 7 days; reconnect from Settings or publish the consent screen.
5. These are sensitive scopes. Publishing the app for other users requires Google's OAuth verification.

## 3. Create the Android OAuth client

**APIs & Services → Credentials → Create credentials → OAuth client ID → Android**

| Field | Value |
|---|---|
| Package name | `com.tubepilot.app` (release) or `com.tubepilot.app.debug` (debug) |
| SHA-1 certificate fingerprint | SHA-1 of the key that signs the APK (see BUILD.md step 4) |

Create one client per signing key (debug, your release key, Play App Signing key). No client ID is compiled into the
app: Google matches the package name and certificate. If sign-in fails with **status 10 (DEVELOPER_ERROR)**, the
package name or SHA-1 does not match.

## 4. Quota

- Default allocation: 10,000 standard units per day, plus separate daily call buckets for `videos.insert` and
  `search.list` (see the "YouTube quota" card in Settings, and
  <https://developers.google.com/youtube/v3/determine_quota_cost>).
- Check your real limits in **APIs & Services → YouTube Data API v3 → Quotas** and edit them in TubePilot Settings
  if they differ. Quota resets at midnight Pacific Time.
- Request more quota with the YouTube API quota extension form if you publish daily.

## 5. Audit: why your uploads may stay private

> **Videos uploaded through an unverified Google Cloud project may be locked to private until the project passes
> YouTube's API compliance audit.**

This is a YouTube policy for API projects created after July 2020. Until your project is audited:

- Uploads succeed but YouTube may force `privacyStatus = private` (TubePilot logs `upload.locked_private` when this
  happens).
- You can still publish manually in YouTube Studio.

To lift it, submit the **YouTube API Services audit and quota extension form** from the YouTube API Services
developer support pages. Describe TubePilot as a single-creator tool that uploads that creator's own original videos
with explicit approval. Google reviews the project's compliance with the YouTube API Services Terms of Service and
Developer Policies.

## 6. Custom thumbnails

`thumbnails.set` works only for channels with a verified phone number / intermediate features enabled
(<https://www.youtube.com/verify>). TubePilot keeps uploading and logs `thumbnail.failed` otherwise.
