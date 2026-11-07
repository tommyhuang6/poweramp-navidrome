# Changelog

## v0.2.0

A bug-fix and safety release after an audit of v0.1.0. Updating is recommended for everyone, especially if your phone is set to Arabic, Persian or another language with its own digits, or if you use a self-signed certificate.

### Fixed

- **Plays were lost after a password change.** Plays waiting to be sent (offline, or while the server was down) were deleted when Navidrome answered "wrong username or password". They now stay in the queue and are sent after you log in again. A play is only dropped when the server says the song doesn't exist.
- **Synced lyrics didn't work on phones set to Arabic, Persian, Bengali and similar languages.** The lyric timestamps were written with that language's digits (`[٠٣:١٢.٥٠]`), which Poweramp can't read. They are now always written as `[03:12.50]`. Track file names had the same issue.
- **A broken server answer could wipe the library from Poweramp.** If the server listed no songs (an error, a server still starting, a different Subsonic server), every song that wasn't cached was removed, together with its Poweramp play counts and playlist entries. The sync now stops with a message and leaves the library as it is.
- **Subsonic servers other than Navidrome could sync no songs.** The app lists the library with one search, which some servers answer with nothing. When that search returns fewer songs than the albums report, the app now reads the library album by album.

### Security

- **https by default.** An address typed without `http://` or `https://` is now tried as https first. Plain http is used only for home-network addresses (192.168.x.x, 10.x.x.x, Tailscale, `.local`, `.lan`…). If you really run an internet-facing server on plain http, type `http://` yourself: the app then shows a warning that your login can be read on the way.
- **"Accept untrusted HTTPS certificates" now trusts one certificate, not every certificate.** It used to accept any certificate for any address, so anyone on the same network could pretend to be your server. The app now remembers the certificate your server shows on the first connection and refuses any other. If you renew a self-signed certificate, turn the option off and on again. If you already had the option on, your certificate is remembered on the first connection after updating.
- The build workflow's GitHub Actions are pinned to exact commits, since that job holds the release signing key.

### Changed

- **Logging in to a different server or user clears the old one's data** from the phone: library, cached songs, covers, ratings and unsent plays. Logging back in to the same account (for example after a password change) keeps everything.
- **"Offline downloads only on Wi-Fi" is checked during the download**, not only at the start: offline downloads pause when the phone leaves Wi-Fi. The "Rest of the album" download-ahead fetches just the next song while on mobile data.
- Album covers are no longer downloaded twice when Poweramp and a finished download ask for the same cover at the same time.

### Updating

Install the APK over v0.1.0; your library, cache and settings stay. The signing key is the same as before (see [Verifying the APK](README.md#verifying-the-apk)).

## v0.1.0

First release.
