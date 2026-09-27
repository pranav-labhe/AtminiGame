# Implementation Plan - Vector Backgrounds & XML Animations

Extract programmatic canvas drawings of background elements (towers, cyber-trees, orbs, floor) into Vector Drawable XML files (`<vector>`), and integrate them into `GameView`.

## Proposed Changes

### Vector Drawables (`res/drawable/`)
- `bg_tower.xml`
- `bg_cyber_tree.xml`
- `bg_orb.xml`
- `bg_floor.xml`

### Game View (`com.pranav.atminigame`)
- Modify `GameView.kt` to load and draw these vector drawables.

## Verification Plan
- Build project with `gradle_build`.
