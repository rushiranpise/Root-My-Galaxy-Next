# The launcher icon, once

Two APKs ship this icon: the app, and the `:dfr` helper that gets installed as a system app. They sit
side by side in a launcher, and they are one product, so they draw the same icon.

It lives outside both modules because a copy in each is a copy that drifts: the helper's icon would go
on being the old one until somebody happened to notice two different app icons for the same install.
Both `app/build.gradle.kts` and `dfr/build.gradle.kts` add this directory as a `main` resource source
instead, and `StageTwoIdentityTest` fails if either module stops reading it from here.

```
drawable/ic_launcher_foreground.xml   the mark
values/colors.xml                     launcher_background, which is the icon's own background
mipmap-anydpi/ic_launcher.xml         the flat icon for anything older than an adaptive one
mipmap-anydpi-v26/ic_launcher.xml     the adaptive icon
mipmap-anydpi-v33/ic_launcher.xml     the adaptive icon with its monochrome layer
```

Nothing here is referenced by name from code: `@mipmap/ic_launcher` is what both manifests ask for, and
`@drawable/ic_launcher_foreground` / `@color/launcher_background` are internal to these files.
