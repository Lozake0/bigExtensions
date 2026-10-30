<h1 align="center">bigExtensions</h1>

<p align="center">Manga sources for <a href="https://mihon.app">Mihon</a> and forks.</p>

<p align="center">
  <a href="https://discord.gg/GZvyZ3zCHV"><img alt="Discord" src="https://img.shields.io/badge/Discord-join%20the%20server-5865F2?logo=discord&logoColor=white"></a>
</p>

## Install

Open **More → Settings → Browse → Extension repos** and add:

```
https://biglongs1.gitlab.io/bigExtensions/index.json
```

The app will list every source. Updates arrive through the same screen.

The repo moved from GitHub to GitLab. If you added an older URL (any `raw.githubusercontent.com`
one, under `BigLongs0`, `BigLongss` or `Biglongs1`), remove it and add the one above to keep
receiving updates. The installed extensions keep working, since they are signed with the same key.

Forks lag behind on repo support, so if the app rejects the URL, check that it is
up to date. Yokai, for instance, only accepts this format from 1.10.0 onwards.

## Requests

Want a source that is not here? [Open a request](https://gitlab.com/biglongs1/bigExtensions/-/issues/new?issuable_template=source_request).

Before asking, check that the site is not already covered by [keiyoushi](https://github.com/keiyoushi/extensions), which is a far larger repo and the better home for most sources. This one exists for sources I maintain closely, so requests are accepted based on how reliably I can keep them alive, not on how popular the site is.

A source is unlikely to be accepted when it:

- requires a paid account to read anything
- hosts content that is not legally distributable
- is a mirror of a site already listed here

## Reporting a broken source

Sites change often, and a source that worked yesterday can break without notice. [Report it here](https://gitlab.com/biglongs1/bigExtensions/-/issues/new?issuable_template=broken_source) with the extension version and what exactly stopped working, since browsing, search and reading tend to break independently of each other.

## Contributing

Merge requests are welcome, whether it is a fix for a broken source or a new one.

1. Fork the repo and branch off `main`.
2. Make the change and bump `versionCode` in the source's `build.gradle.kts`, otherwise the app will not offer the update.
3. Build and run it against a device before opening the merge request.
4. Open the merge request and fill in the checklist.

Sources live in `src/<lang>/<name>` and follow the same conventions as [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source), so its [CONTRIBUTING](https://github.com/keiyoushi/extensions-source/blob/main/CONTRIBUTING.md) applies here too and is worth reading before writing any code.

If a source is broken and you know why but do not want to write the fix, say so in the issue. A pointer to the request that changed is usually most of the work.

## Support

Issues are the best place for anything about a source, since it stays searchable for whoever hits the same problem later. For everything else, join the [Discord server](https://discord.gg/GZvyZ3zCHV) or reach me here:

<p align="left">
  <a href="https://discord.com/users/1254123038656430189">
    <img alt="Discord" src="https://img.shields.io/badge/Discord-biglongs-5865F2?style=for-the-badge&logo=discord&logoColor=white">
  </a>
  <a href="https://t.me/donttry999">
    <img alt="Telegram" src="https://img.shields.io/badge/Telegram-%40donttry999-26A5E4?style=for-the-badge&logo=telegram&logoColor=white">
  </a>
</p>

## Credits

Build infrastructure comes from [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source), under Apache 2.0.
