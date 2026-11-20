# Freeview Live Hub

A Fire TV / Firestick app that feels like normal telly for UK free-to-air channels:

- **Live TV** – channels in Freeview order (HD where available), with now/next and progress bars.
  Regional channels are set to the North West: BBC One North West and ITV1 Granada.
- **TV Guide** – a scrolling programme grid. Choose any programme and the channel starts playing.
- **On Demand** – shortcuts into BBC iPlayer, ITVX, Channel 4, My5 and U.

Playback happens in the broadcasters' own apps (iPlayer, ITVX, Channel 4, My5), so those need to be
installed on the Firestick from the Amazon Appstore. Pressing **Back** in them returns to the guide.

> The programme listings are currently **sample data**. A real guide needs a licensed EPG feed.

## Remote controls

| Button | Action |
| --- | --- |
| D-pad | Move around |
| Select (OK) | Watch the channel |
| Back | Go back to Live TV, then exit |
| Rewind / Fast-forward | Channel down / up |

## Getting the APK

Every push to `main` builds the app with GitHub Actions and publishes `FreeviewLiveHub.apk`
on the [Releases](../../releases/latest) page.

### Installing on a Firestick

1. On the Firestick: **Settings → My Fire TV → Developer options → Install unknown apps**
   (if Developer options is hidden, go to **About** and press Select on the device name 7 times).
2. Get the APK onto the stick, either:
   - **Public repo:** install the *Downloader* app, allow it to install unknown apps, and enter
     `https://github.com/Jden85/freeview-live-hub/releases/latest/download/FreeviewLiveHub.apk`
   - **Private repo:** download the APK from Releases on your computer or phone, then send it with
     the *Send Files to TV* app.
3. Open the APK to install, then find **Freeview Live Hub** under *Your Apps & Channels*.

## Project layout

- `web/index.html` – the whole TV interface (HTML/CSS/JS). Open it in a browser to try it with arrow keys.
- `app/` – the Android wrapper: a full-screen WebView that hands links off to the broadcaster apps.
- `.github/workflows/build-apk.yml` – the build.

## Signing

Releases are signed with a key stored in the repository secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD` and `KEY_ALIAS`, so each new version installs over the old one.
