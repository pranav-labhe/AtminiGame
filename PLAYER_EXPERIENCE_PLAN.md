# Atmini Player Experience Plan

## Product direction

Build a futuristic, dreamy cyber-arcade runner where the world becomes more alive as Atmini restores it. The experience should feel expressive, fast, musical, and replayable, with hazards and controls that remain easy to read. The game earns repeat play through satisfying skill growth, fresh runs, personal goals, and player expression—not punishments for taking a break or pressure to spend.

## Current foundation

Already present in the project: landscape touch controls, running and jumping, orb collection and scoring, scrolling camera, AI-controlled orb seeking, combat threats and a boss, five transitioning background zones, particles and effects, procedural music, haptics, and a run completion/defeat flow.

## Experience principles

- Make every important action readable through shape, motion, sound, and color.
- Put optional spectacle behind settings; preserve a low-effects and reduced-motion experience.
- Keep the core run understandable without lore, social features, or AI mode.
- Reward skill, exploration, and personal goals. Avoid punitive streaks, energy timers, manipulative notifications, and paid random-reward mechanics.
- Let players choose their intensity: chill, standard, and high-energy presentation should not require changing the game's core identity.

## Roadmap

### 1. First session and core feel — highest priority

- Add a short, replayable tutorial for move, jump, collect, dodge, attack, and restart. Teach through play with clear skip and replay options.
- Add pause/resume and a reliable restart flow, including a confirmation when leaving an active run.
- Tune touch controls; allow repositioning and resizing. Add controller support and remappable inputs where practical.
- Improve jump responsiveness with input buffering and coyote time. Test control comfort across screen sizes and aspect ratios.
- Make pickups, damage, recovery, boss phases, and AI actions legible with distinct animations, sounds, and captions/icons.
- Add clear pre-attack warnings and distinct hazard silhouettes. Ensure hazards do not rely on color alone.
- Provide adjustable difficulty, optional practice/checkpoint modes, short-run and full-journey choices, and accessible palettes.

### 2. Futuristic identity and audiovisual polish

**Music and sound**

- Give each zone a recognizable sound palette: for example, dreamy hyperpop, liquid DnB, cinematic synthwave, and glassy ambient synths. Treat genres as a cohesive original score rather than a playlist of disconnected tracks.
- Make tracks evolve with the run: add layers for speed, orb streaks, combat, boss phases, and clean dodges. Transition layers on the beat and let incoming-zone instruments preview the next area.
- Give each boss a learnable audio motif. Use a restrained beat-drop for reveals, a musical accent for perfect dodges, and a victory reprise after a boss is defeated.
- Let orb streaks build a short musical phrase. Add optional synthetic vocal chops and a brief run-end music montage.
- Offer chill, focus, and high-energy mixes; separate music, effects, and voice levels; include previews, headphone-friendly mixing, and a quiet-but-vibey mode that softens sharp transients.
- Keep audio cues optional and captions available. Include mono-safe behavior and avoid requiring stereo hearing to locate threats.

**Visual identity**

- Establish a consistent holographic language: translucent telemetry panels, iridescent accents, scan lines, expressive futuristic type, chrome/glass surfaces, and restrained sticker-like reactions.
- Give zones distinct title cards and subtle UI skins. Use skippable portal transitions and cinematic boss entrances.
- Animate pickups with a quick squash, scan, sparkle burst, satisfying score pop, and beat-aware accent. Keep small, large, bonus, and dangerous objects distinguishable by silhouette as well as color.
- Show restoration in the environment: noisy, disconnected architecture becomes brighter and more connected as the player progresses.
- Add adjustable trails, afterimages, particles, screen shake, flashes, lensing, and color separation. Default to readable effects and provide reduced-motion/low-effects modes.
- Add a living title screen, optional emote reactions, victory poses, and a small unlockable companion that reacts to gameplay.
- Use music-reactive scenery only in subtle background elements; keep hazard shapes and timing stable.

### 3. More varied runs and mastery

- Vary orb routes, hazard combinations, and tested encounter layouts so runs feel fresh while remaining fair.
- Give zones optional mechanics—such as marked low-gravity sections, moving platforms, wind, wall-running, or gravity shifts—with clear introduction and safe practice.
- Explore a phase dash, rechargeable shield, signal-hacking encounters, and a short-lived movement echo as optional abilities. Prototype one at a time and ensure each adds a readable decision rather than button overload.
- Add combat choices, such as purifying a threat for score or redirecting its energy for a temporary boost.
- Offer separate, clearly labeled modifiers such as mirror controls, extra bounce, oversized orbs, boss rush, and speedrun. Keep modifiers out of standard records if they change difficulty materially.
- Add optional objectives: collect every orb, avoid damage, beat a boss quickly, or complete a zone with a particular ability.
- Add practice sections/checkpoints and a friendly ghost race against a player's own best run.
- Rotate a daily seeded challenge and weekly challenge with fixed rules so comparisons are fair. Allow sharing a seed and modifier set with friends.

### 4. Endless Ascension mode

Add an optional mode with no final level. Players can continue through repeating world cycles for as long as they choose. Keep the standard campaign/run available alongside it.

- **Handcrafted procedural assembly:** build each stretch from tested platform, orb, and hazard patterns. Vary order and combinations while preserving fair landing space, readable warnings, and safe recovery sections.
- **No speed ramp:** keep Atmini's movement speed and the baseline pace fixed as distance increases. Raise challenge through more involved but telegraphed hazard combinations, route decisions, and encounter patterns—not by making the game run faster.
- **Endless world cycles:** rotate through the five zones, then remix their palettes, music layers, structures, and selected mechanics so returning to Dawn feels like a fresh cycle.
- **Optional build choices:** at clear intervals, offer a small choice of temporary run upgrades, such as a longer dash, one shield charge, or stronger orb magnetism. Explain the trade-off and keep options balanced.
- **Milestones without forced resets:** celebrate distance, completed zone loops, boss defeats, collection streaks, and personal records while letting the player continue.
- **Risk-and-reward routes:** offer a safer route and a more demanding route with bonus rewards. Make the risk visible before the choice.
- **Boss rotations:** revisit bosses with different, readable attack combinations or modifiers rather than simply increasing their health.
- **Optional run modifiers:** rotate choices like low gravity, alternate hazard patterns, or bonus big-orb rewards. Keep them opt-in and distinguish modified records from standard records.
- **Personal-best ghost:** let players race their own longest or highest-scoring Endless run, with controls to hide or recolor the ghost.
- **Quick recovery loop:** after defeat, show a concise recap and a prominent retry option. Never require a long post-run flow before another attempt.
- **Player-controlled stopping point:** provide pause and exit at all times, and make milestones feel complete even when a player ends a session.

The mode should create variety and mastery without using speed escalation, punitive streaks, or rewards that vanish when players stop. Test procedural combinations for fairness and readability before increasing their variety.
### 5. Healthy replay and progression

Use replay loops that create a clear next goal and make improvement visible:

- Track personal bests for score, time, longest orb streak, clean zones, boss time, and damage avoided. Show a concise run summary with route highlights and optional objectives.
- Give players multiple goals at once: improve a personal record, finish a challenge, explore a route, or unlock a cosmetic. Let them choose which goal matters.
- Add a mastery path with small, attainable milestones for movement, collecting, dodging, and combat. Grant cosmetics, lore, music motifs, and profile flair—not paid power advantages.
- Add a collectible archive for transmissions, digital artifacts, creatures, threats, zones, and music motifs. Make collection completion optional and avoid missable one-time content.
- Let players unlock and save loadout presets: outfit, trail, pickup effect, companion, emote, and music mood. Include themed sets such as arcade, cyber-fairy, chrome, and dreamy pastel.
- Add weekly/seasonal cosmetic themes and community goals that remain available long enough to avoid fear of missing out. Do not remove core content when a season ends.
- Add a light rival target, personal-best ghost, and opt-in leaderboard categories such as clean run or fastest boss. Keep social comparison optional.
- Consider asynchronous duet scores and community playlist voting after the solo loop is strong.
- Make return reminders opt-in and infrequent. Never penalize missed days; daily challenges should remain playable later or rotate without breaking progress.

### 6. Shareable moments and social play

- Create a shareable “signal print” at run end: score, zone reached, selected look, best streak, and a composed screenshot.
- Add photo mode with pause, HUD hiding, bounded camera movement, poses, and cosmetic effects.
- Save an optional short replay of a boss finish or standout run moment, with music and captions.
- Add a neon route-map replay timeline showing pickups, damage, boss events, and the best streak.
- Let players share challenge seeds, ghost runs, and custom loadouts. Add moderation and privacy controls before any player-created message feed.
- Explore community transmissions, a rotating soundtrack vote, and cosmetic gifting only after the core game and privacy model are ready.

## “Compelling, not compulsive” engagement goals

The game should make players want another run because they are learning and expressing themselves—not because they fear losing progress.

- Use short runs with a satisfying ending and a visible “one more goal” option, while making it easy to stop.
- Make improvement concrete: show a new personal best, a cleaner dodge, a mastered mechanic, or progress toward a chosen unlock.
- Keep rewards predictable and earned through play. Avoid loot boxes, deceptive near-misses, pay-to-win upgrades, forced ads, energy systems, and streak loss.
- Give daily/weekly challenges a generous window and no penalty for skipping them. Do not use urgent countdowns for core rewards.
- Keep cosmetic purchases, if ever added, transparent and separate from gameplay power. Do not sell randomized rewards.
- Let players disable notifications, social comparison, flashing effects, haptics, and music-reactive effects independently.
- End sessions gracefully with a run summary and clear pause/exit choices rather than an endless feed or forced immediate restart.

## Suggested release slices

1. **Feel and clarity:** tutorial, pause/restart, control tuning, hazard warnings, settings for audio/haptics/effects, device-size review.
2. **Signature style:** zone music identity, reactive music layers, pickup/boss presentation, HUD typography, restoration visuals, accessible contrast and silhouettes.
3. **Replay loop:** run summary, personal records, optional objectives, practice/checkpoints, seeded challenge, personal-best ghost.
4. **Endless mode:** handcrafted procedural patterns, fair route choices, fixed movement speed, zone remixes, and optional run builds.
5. **Expression:** cosmetics, loadout presets, companion/emotes, photo mode, signal-print sharing, artifact archive.
6. **Expansion:** new zone mechanics, combat choices, additional encounter patterns, asynchronous social challenges, community features.

## How to choose what ships

For each feature, first prototype the smallest player-visible version. Check whether players understand it without explanation, whether it improves a run or gives a meaningful replay goal, whether it stays readable with effects disabled, and whether it remains fun after repeated sessions. Prioritize observed player friction and delight over feature count.


