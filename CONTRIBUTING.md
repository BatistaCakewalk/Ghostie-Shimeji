# Contributing to Ghostie-Shimeji

Thanks for wanting to help. This is a small one-character fork — keep that in mind.

## Before you start

Open an issue first if you're planning something big. No point spending hours on a PR that goes in a different direction than where the project is headed.

Small fixes (typos, obvious bugs) — just PR it, no need to ask.

## Branches

`main` is stable, what ships. Active development happens on `canary/*`, so base your work off the current canary branch. PRs go to canary for now, not `main`. Larger work stacks as `canary/feature/*` branches, merged bottom-up.

## Getting set up

You'll need Java 25 and Maven. Then:

```
git clone https://github.com/BatistaCakewalk/Ghostie-Shimeji.git
cd Ghostie-Shimeji
mvn -DskipTests package
```

The built app lands in `target/` (`Shimeji-ee.jar`, `Shimeji-ee.exe`, and the zip).

## Pull requests

Branch off canary. Make sure it builds before opening the PR. Seriously. Keep it focused — one thing per PR — and write a decent description of what changed and why. Merges are squash, so keep the branch history tidy enough to squash cleanly.

## Code style

Match what's already there: SLF4J `Logger` per class for all logging (no `System.out.println` in production code), `final` method parameters, and Javadoc on new classes and non-trivial methods.

## Sprites

Only `img/NigelShimeji/` is tracked — that's the character, so new frames go there. 192x192 canvas, anchor 96,200 (feet). Run the app and watch the thing move before opening the PR.

## License

By contributing, you agree your code falls under the project's [license](LICENSE.txt).
