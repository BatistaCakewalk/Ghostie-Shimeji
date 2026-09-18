# Nigel Tuning Guide

How to tweak Nigel's behavior: frequencies, grasp, telekinesis, and image sets. User-facing stuff (install, run, troubleshoot) stays in `README.md`.

Each mascot resolves `img/[NAME]/conf/actions.xml` → `conf/actions.xml` fallback, same for `behaviors.xml`. Required actions/behaviors: `Fall`, `Dragged`, `Thrown`, `ChaseMouse`.

## Frequencies

**Frequencies** are weights: `P = Frequency / sum(Frequencies)` inside the active `Condition`. Example floor:

```xml
<Condition Condition="#{mascot.environment.floor.isOn(mascot.anchor)}">
  <Behavior Name="StandUp" Frequency="6"/>
  <Behavior Name="Walk" Frequency="7"/>
  <Behavior Name="ChaseMouse" Frequency="7"/>
  <Behavior Name="CatchMouse" Frequency="2"/>
  <Behavior Name="Telekinesis" Frequency="3"/>
</Condition>
```
<!-- 6+7+7+2+3=25: StandUp 24%, Walk 28%, ChaseMouse 28%, CatchMouse 8%, Telekinesis 12% -->

Raise `CatchMouse` to `10` → `10/33≈30%` of floor picks. `Grasp` itself is `Duration="20000"` — must be `> CuddleIdle+Duration (~18600)` to allow full cuddle.

## Grasp attributes

On `<Action Name="Grasp" ...>`:

`MaxStruggle` (plus a random `0..MaxStruggleBonus` bonus HP per grasp, default 600), `Regen`, `MissThreshold (150→450 on tackle)`, `GraspOffsetY`, `FastThreshold/Multiplier`, `FuriousThreshold/Multiplier`, `TackleStaggerTicks`, `TackleKnockback`, `CuddleIdleTicks`, `CuddleDurationTicks`, `CuddleShakeThreshold`, `CuddleShakeCount`, `SwallowChance`, `SwallowClickCount`, `SwallowClickWindow`, `SickPhase1Ticks`, `SickPhase2Ticks`, `SpitSpeedX`, `SpitSpeedY`, `SpitGravity`, `SpitBounce`, `SickClickPower`, `SickBurpClicks` (clicks needed before the burp, default 6), `SickPowerCap` (bonus power cap, default 3.0).

While swallowed the cursor stays pinned dead center on Nigel (escape clicks only register on his window); an hour with zero registered clicks forces sickness as an anti-softlock. The spit-fling leaves a fading saliva-droplet trail and floor-bounces while it has bounces banked from sick clicks.

Big cursors: past `MaxCatchableCursorSize` (default 96px) Nigel won't pounce at all (he'll still chase). Below that, swallowing scales: 32px needs the base `SwallowClickCount`, up to triple at 96px. Over 64px he shows the stuffed `BloatBig*.png` sprites when the set provides them (`BloatBigStand.png`, `BloatBigStand2.png`, `BloatBigWalk1.png`, `BloatBigWalk2.png` — 192x192, anchor 96,200, optional, falls back per file). Tele-mouse reels slow down with cursor size (full speed at 32px, quarter floor).

## Telekinesis attributes

On `<Action Name="Telekinesis" ...>`, `Duration="400"`:

`TeleRadiusX`, `TeleRadiusY`, `TeleLift`, `TeleReturnTicks`, `TeleMode` (`window`/`mouse`/`nigel`, else 50/50 mouse/window + 30% fellow Nigel).

Notes:

* Recently grabbed windows sit out for 60s so the same window isn't yoinked over and over.
* Maximized, minimized, fullscreen, and fully occluded windows are never grabbable (yoinking one would mean un-maximizing your app — rude even for Nigel).

## Image sets

Drop a folder mimicking `img/NigelShimeji` (same filenames) into `img/`; `img/unused/` is ignored. `conf/settings.properties` / Image Set Chooser remembers active sets. All Nigel sprites are 192×192 with ground baseline `ImageAnchor=96,200`.
